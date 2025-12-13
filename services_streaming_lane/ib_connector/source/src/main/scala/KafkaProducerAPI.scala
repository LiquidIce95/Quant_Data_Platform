package src.main.scala

import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import java.util.Properties

class KafkaProducerApi(
	bootstrapServers: String = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
	clientId: String = sys.env.getOrElse("KAFKA_CLIENT_ID", "ib-producer"),
	schemaRegistryUrl: String = sys.env.getOrElse("SCHEMA_REGISTRY_URL", "http://schema-registry:8081")
) {

	private val props: Properties = {
		val p = new Properties()
		p.put("bootstrap.servers", bootstrapServers)
		p.put("client.id", clientId)

		p.put("acks", "all")
		p.put("enable.idempotence", "true")
		p.put("retries", "2147483647")
		p.put("max.in.flight.requests.per.connection", "5")
		p.put("delivery.timeout.ms", "120000")
		p.put("request.timeout.ms", "30000")

		p.put("linger.ms", "150")

		p.put("key.serializer", "org.apache.kafka.common.serialization.LongSerializer")
		p.put("value.serializer", "io.confluent.kafka.serializers.KafkaAvroSerializer")

		p.put("schema.registry.url", schemaRegistryUrl)
		p.put("auto.register.schemas", "false")

		p
	}

	private val producer =
		new KafkaProducer[java.lang.Long, AnyRef](props)

	def sendAvro(topic: String, key: Long, value: GenericRecord): Unit = {
		val record =
			new ProducerRecord[java.lang.Long, AnyRef](topic, Long.box(key), value)
		producer.send(record).get()
	}
}
