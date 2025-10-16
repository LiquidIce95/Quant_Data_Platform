package com.yourorg.spark

import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession}
import org.apache.spark.sql.streaming.{StreamingQuery, Trigger}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import java.util.UUID

object ReadTickLastPrint {
  def main(args: Array[String]): Unit = {
    // ===== Spark session configured per ClickHouse docs =====
    val chHost     = sys.env.getOrElse("CH_HOST", "clickhouse")
    val chHttpPort = sys.env.getOrElse("CH_HTTP_PORT", "8123")
    val chUser     = sys.env.getOrElse("CH_USER", "spark")
    val chPass     = sys.env.getOrElse("CH_PASSWORD", "sparkpass")
    val chDb       = sys.env.getOrElse("CH_DATABASE", "quant")
    val chTable    = sys.env.getOrElse("CH_TABLE", "market_trades")

    val spark = SparkSession.builder()
      .appName("ReadTicklastPrintSparkConnectionTest")
      // === ClickHouse catalog (docs) ===
      .config("spark.sql.catalog.clickhouse", "com.clickhouse.spark.ClickHouseCatalog")
      .config("spark.sql.catalog.clickhouse.host", chHost)
      .config("spark.sql.catalog.clickhouse.protocol", "http")
      .config("spark.sql.catalog.clickhouse.http_port", chHttpPort)
      .config("spark.sql.catalog.clickhouse.user", chUser)
      .config("spark.sql.catalog.clickhouse.password", chPass)
      .config("spark.sql.catalog.clickhouse.database", chDb)
      .config("spark.clickhouse.write.format", "json") // docs example uses json format
      // === Critical for your error: ignore untranslateable partition/sharding transforms ===
      .config("spark.clickhouse.ignoreUnsupportedTransform", "true")
      // Avoid repartition-by-partition-keys since transform is ignored
      .config("spark.clickhouse.write.repartitionByPartition", "false")
      .getOrCreate()

    spark.sparkContext.setLogLevel("INFO")
    import spark.implicits._

    // ===== Kafka source (as-is) =====
    val bootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "dev-kafka-kafka-bootstrap.kafka:9092")
    val starting  = sys.env.getOrElse("KAFKA_STARTING_OFFSETS", "latest")

    val kafkaDF = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", bootstrap)
      .option("subscribe", "ticklast")
      .option("startingOffsets", starting)
      .load()

    // ===== Parse payload to final schema expected by ClickHouse table =====
    val payloadSchema = StructType(Seq(
      StructField("event_time", StringType, nullable = true),
      StructField("ib_time", LongType, nullable = true),
      StructField("extraction_time", LongType, nullable = true),
      StructField("trading_symbol", StringType, nullable = true),
      StructField("source", StringType, nullable = true),
      StructField("source_system", StringType, nullable = true),
      StructField("price", DoubleType, nullable = true),
      StructField("size", StringType, nullable = true),
      StructField("event_id", StringType, nullable = true)
    ))

    val makeUUID = udf(() => java.util.UUID.randomUUID().toString)

    val parsed = kafkaDF
      .select(col("value").cast("string").as("value"))
      .withColumn("json", from_json(col("value"), payloadSchema))
      .select(
        current_timestamp().as("ingestion_time"),
        col("json.*")
      )
      .withColumn("event_time_iso", to_timestamp(col("event_time")))
      .withColumn("event_time_num_sec",
        when(col("event_time").rlike("^\\d+(\\.\\d+)?$"), col("event_time").cast("double"))
      )
      .withColumn("event_time_final",
        coalesce(
          col("event_time_iso"),
          to_timestamp(col("event_time_num_sec")),
          to_timestamp(col("ib_time").cast("double")),
          expr("to_timestamp(extraction_time/1000.0)"),
          current_timestamp()
        )
      )
      .withColumn("source_final", coalesce(col("source"), col("source_system"), lit("IB")))
      .withColumn("trading_symbol_final", coalesce(col("trading_symbol"), lit("UNKNOWN_SYMBOL")))
      .withColumn("size_final",
        when(col("size").isNotNull && length(trim(col("size"))) > 0, col("size").cast("long")).otherwise(lit(0L))
      )
      .withColumn("price_final", coalesce(col("price"), lit(0.0)))
      .withColumn("event_id_final", coalesce(col("event_id"), makeUUID()))
      .select(
        col("ingestion_time").cast("timestamp").as("ingestion_time"),
        col("event_time_final").cast("timestamp").as("event_time"),
        col("trading_symbol_final").as("trading_symbol"),
        col("source_final").as("source"),
        col("price_final").as("price"),
        col("size_final").as("size"),
        col("event_id_final").as("event_id")
      )

    // ===== Exactly like docs: write via catalog with DataFrameWriterV2 =====
    val tableIdent = s"clickhouse.${chDb}.${chTable}"

    val q = parsed.writeStream
      .trigger(Trigger.ProcessingTime("1 second"))
      .outputMode("append")
      .foreachBatch { (batch: DataFrame, _: Long) =>
        if (!batch.isEmpty) {
          // Use V2 writer against the registered catalog (no explicit .format)
          batch.writeTo(tableIdent).append()
        }
      }
      .start()

    q.awaitTermination()
  }
}
