package com.yourorg.spark

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.functions._

import za.co.absa.abris.config.AbrisConfig
import za.co.absa.abris.avro.functions.from_avro

object ReadTickLastPrint {

	def main(args: Array[String]): Unit = {

		val spark = SparkSession.builder()
			.appName("ReadDerivativesTickMarketData")
			.getOrCreate()

		spark.sparkContext.setLogLevel("INFO")

		val bootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "dev-kafka-kafka-bootstrap.kafka:9092")
		val starting  = sys.env.getOrElse("KAFKA_STARTING_OFFSETS", "latest")
		val topic     = sys.env.getOrElse("KAFKA_TOPIC", "derivatives_tick_market_data")

		val schemaRegistryUrl =
			sys.env.getOrElse("SCHEMA_REGISTRY_URL", "http://schema-registry.avro-schema-registry:8081")

		val abrisConfig =
			AbrisConfig
				.fromConfluentAvro
				.downloadReaderSchemaByLatestVersion
				.andTopicNameStrategy(topic)
				.usingSchemaRegistry(schemaRegistryUrl)

		val kafkaDF = spark.readStream
			.format("kafka")
			.option("kafka.bootstrap.servers", bootstrap)
			.option("subscribe", topic)
			.option("startingOffsets", starting)
			.load()

		val decoded = kafkaDF
			.select(from_avro(col("value"), abrisConfig).as("r"))
			.select(col("r.*"))

		val db =
			sys.env.getOrElse("CH_DATABASE", "realtime_store")

		val table =
			sys.env.getOrElse("CH_TABLE", "derivatives_tick_market_data")

		val tableIdent =
			s"clickhouse.${db}.${table}"

		val q = decoded.writeStream
			.trigger(Trigger.ProcessingTime("1 second"))
			.outputMode("append")
			.foreachBatch { (batch: DataFrame, _: Long) =>
				if (!batch.isEmpty) {
					batch.writeTo(tableIdent).append()
				}
			}
			.start()

		q.awaitTermination()
	}
}
