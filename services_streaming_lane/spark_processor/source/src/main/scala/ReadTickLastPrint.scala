package com.yourorg.spark

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.streaming.StreamingQuery
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.functions._
import java.util.UUID

object ReadTicklastPrint {
  def main(args: Array[String]): Unit = {
    val appName = "ReadTicklastPrintSparkConnectionTest"
    val runId   = UUID.randomUUID().toString.take(8) // short tag per run
    val host    = sys.env.getOrElse("HOSTNAME", "unknown-host")

    val spark : SparkSession = SparkSession.builder()
      .appName(appName)
      .getOrCreate()

    spark.sparkContext.setLogLevel("INFO")

    val bootstrap : String = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "dev-kafka-kafka-bootstrap.kafka:9092")
    val starting  : String = sys.env.getOrElse("KAFKA_STARTING_OFFSETS", "latest") // or "earliest"

    val kafkaDF : DataFrame = spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", bootstrap)
      .option("subscribe", "ticklast")
      .option("startingOffsets", starting)
      .load()

    val decoded : DataFrame = kafkaDF
      .select(
        lit(appName).as("printed_by"),
        lit(host).as("host"),
        lit(runId).as("run_id"),
        col("topic"),
        col("partition"),
        col("offset"),
        col("timestamp").as("kafka_ts"),
        col("key").cast("string").as("key"),
        col("value").cast("string").as("value")
      )
      // convenient single marker column for grepping
      .withColumn("marker", concat(lit("FROM "), col("printed_by"), lit(" @ "), col("host"), lit(" #"), col("run_id")))

    val q : StreamingQuery = decoded.writeStream
      .format("console")
      .option("truncate", "false")
      .option("numRows", "50")
      .trigger(Trigger.ProcessingTime("1 second"))
      .outputMode("append")
      .start()

    q.awaitTermination()
  }
}
