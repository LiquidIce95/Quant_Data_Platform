package com.yourorg.spark

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.functions._
import org.apache.spark.sql.avro.functions.from_avro

import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets

object ReadTickLastPrint {

	private def httpGet(url: String): String = {
		val u = new URL(url)
		val c = u.openConnection().asInstanceOf[HttpURLConnection]
		c.setRequestMethod("GET")
		c.setConnectTimeout(5000)
		c.setReadTimeout(10000)
		val bytes = c.getInputStream.readAllBytes()
		new String(bytes, StandardCharsets.UTF_8)
	}

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

		val subject =
			sys.env.getOrElse("SCHEMA_SUBJECT", s"${topic}-value")

		val schemaResponse =
			httpGet(s"${schemaRegistryUrl}/subjects/${subject}/versions/latest")

		val schemaJson =
			spark.read.json(spark.sparkContext.parallelize(Seq(schemaResponse)))

		val avroSchema =
			schemaJson.select(col("schema")).head().getString(0)

		val kafkaDF = spark.readStream
			.format("kafka")
			.option("kafka.bootstrap.servers", bootstrap)
			.option("subscribe", topic)
			.option("startingOffsets", starting)
			.load()

		val decoded = kafkaDF
			.select(from_avro(col("value"), avroSchema).as("r"))
			.select(col("r.*"))

		val out = decoded

		val db =
			sys.env.getOrElse("CH_DATABASE", "realtime_store")

		val table =
			sys.env.getOrElse("CH_TABLE", "derivatives_tick_market_data")

		val tableIdent =
			s"clickhouse.${db}.${table}"

		val q = out.writeStream
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
