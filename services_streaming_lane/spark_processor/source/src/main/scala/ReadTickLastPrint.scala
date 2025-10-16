package com.yourorg.spark

import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.apache.spark.sql.streaming.{StreamingQuery, Trigger}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import java.util.UUID

object ReadTickLastPrint {
  def main(args: Array[String]): Unit = {
    val appName = "ReadTicklastPrintSparkConnectionTest"
    val runId   = UUID.randomUUID().toString.take(8)
    val host    = sys.env.getOrElse("HOSTNAME", "unknown-host")

    val spark: SparkSession = SparkSession.builder()
      .appName(appName)
      .getOrCreate()
    spark.sparkContext.setLogLevel("INFO")
    import spark.implicits._

    // ===== Kafka source (unchanged) =====
    val bootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "dev-kafka-kafka-bootstrap.kafka:9092")
    val starting  = sys.env.getOrElse("KAFKA_STARTING_OFFSETS", "latest")

    val kafkaDF: DataFrame = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", bootstrap)
      .option("subscribe", "ticklast")
      .option("startingOffsets", starting)
      .load()

    val decoded: DataFrame = kafkaDF.select(
      lit(appName).as("printed_by"),
      lit(host).as("host"),
      lit(runId).as("run_id"),
      col("topic"),
      col("partition"),
      col("offset"),
      col("timestamp").as("kafka_ts"),
      col("key").cast("string").as("key"),
      col("value").cast("string").as("value")
    ).withColumn("marker", concat(lit("FROM "), col("printed_by"), lit(" @ "), col("host"), lit(" #"), col("run_id")))

    // Console sink (unchanged)
    // val qConsole: StreamingQuery = decoded.writeStream
    //   .format("console")
    //   .option("truncate", "false")
    //   .option("numRows", "50")
    //   .trigger(Trigger.ProcessingTime("1 second"))
    //   .outputMode("append")
    //   .start()

    // ===== Transform value -> market_trades schema (MAKE IT WORK) =====
    // Accept the fields we actually see on the wire.
    val payloadSchema = StructType(Seq(
      StructField("event_time", StringType, nullable = true),      // optional ISO or numeric (seconds)
      StructField("ib_time", LongType, nullable = true),           // seconds since epoch
      StructField("extraction_time", LongType, nullable = true),   // millis since epoch
      StructField("trading_symbol", StringType, nullable = true),  // required logically, but we’ll default if missing
      StructField("source", StringType, nullable = true),
      StructField("source_system", StringType, nullable = true),   // e.g. "interactive_brokers_tws_api"
      StructField("price", DoubleType, nullable = true),
      StructField("size", StringType, nullable = true),            // comes as string "0"
      StructField("event_id", StringType, nullable = true)
    ))

    val makeUUID = udf(() => java.util.UUID.randomUUID().toString)

    val parsed: DataFrame = decoded
      .withColumn("json", from_json(col("value"), payloadSchema))
      .select(
        current_timestamp().cast("timestamp").as("ingestion_time"),
        col("json.*")
      )
      // If event_time is ISO string
      .withColumn("event_time_iso", to_timestamp(col("event_time")))
      // If event_time is numeric seconds-as-string
      .withColumn("event_time_num_sec",
        when(col("event_time").rlike("^\\d+(\\.\\d+)?$"), col("event_time").cast("double"))
      )
      // Build final event_time (NOT NULL): ISO -> numeric seconds -> ib_time(s) -> extraction_time(ms) -> now()
      .withColumn("event_time_final",
        coalesce(
          col("event_time_iso"),
          to_timestamp(col("event_time_num_sec")),
          to_timestamp(col("ib_time").cast("double")),
          expr("to_timestamp(extraction_time/1000.0)"),
          current_timestamp()
        )
      )
      // source: prefer 'source' else 'source_system' else 'IB'
      .withColumn("source_final", coalesce(col("source"), col("source_system"), lit("IB")))
      // symbol: if missing, give a safe default
      .withColumn("trading_symbol_final", coalesce(col("trading_symbol"), lit("UNKNOWN_SYMBOL")))
      // size: string -> long (default 0)
      .withColumn("size_final",
        when(col("size").isNotNull && length(trim(col("size"))) > 0, col("size").cast("long")).otherwise(lit(0L))
      )
      // price: default 0.0
      .withColumn("price_final", coalesce(col("price"), lit(0.0)))
      // event_id: keep if present, else generate UUID
      .withColumn("event_id_final", coalesce(col("event_id"), makeUUID()))
      // Select exactly the ClickHouse schema (types aligned)
      .select(
        col("ingestion_time").cast("timestamp").as("ingestion_time"),
        col("event_time_final").cast("timestamp").as("event_time"),
        col("trading_symbol_final").cast("string").as("trading_symbol"),
        col("source_final").cast("string").as("source"),
        col("price_final").cast("double").as("price"),
        col("size_final").cast("long").as("size"),
        col("event_id_final").cast("string").as("event_id")
      )

    // ===== ClickHouse connection configs (env-driven defaults already good) =====
    val chHost        = sys.env.getOrElse("CH_HOST", "clickhouse")
    val chTcpPort     = sys.env.getOrElse("CH_TCP_PORT", "9000")
    val chDatabase    = sys.env.getOrElse("CH_DATABASE", "quant")
    val chTable       = sys.env.getOrElse("CH_TABLE", "market_trades")
    val chUser        = sys.env.getOrElse("CH_USER", "spark")
    val chPassword    = sys.env.getOrElse("CH_PASSWORD", "sparkpass")
    val chBatchSize   = sys.env.getOrElse("CH_BATCH_SIZE", "100000")
    val chNumRetries  = sys.env.getOrElse("CH_NUM_RETRIES", "3")

    def writeWithNative(batch: DataFrame): Unit = {
      batch.write
        .format("clickhouse")
        .option("url", s"clickhouse://$chHost:$chTcpPort")
        .option("database", chDatabase)
        .option("table", chTable)
        .option("user", chUser)
        .option("password", chPassword)
        .option("batchsize", chBatchSize)
        .option("numPartitions", math.max(1, batch.rdd.getNumPartitions).toString)
        .option("retry", chNumRetries)
        .mode(SaveMode.Append)
        .save()
    }

    val qIngest: StreamingQuery = parsed.writeStream
      .trigger(Trigger.ProcessingTime("1 second"))
      .outputMode("append")
      .foreachBatch { (batch: DataFrame, _: Long) =>
        if (!batch.isEmpty) writeWithNative(batch)
      }
      .start()

    //qConsole.awaitTermination()
    qIngest.awaitTermination()
  }
}
