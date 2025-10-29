package src.main.scala

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties
import java.util.concurrent.Future

/**
  * Thin wrapper around a Kafka producer for publishing JSON strings.
  *
  * Configuration:
  *   - bootstrap servers from env `KAFKA_BOOTSTRAP_SERVERS` (default "localhost:9092")
  *   - client id from env `KAFKA_CLIENT_ID` (default "ib-producer")
  *   - acks = "all", String key/value serializers
  *   - batching controlled by envs `KAFKA_LINGER_MS` (default "5") and `KAFKA_BATCH_SIZE` (default "32768")
  *
  * Assumptions:
  *   - Keys and values are UTF-8 strings.
  *   - Caller is responsible for invoking [[flush]] on shutdown-sensitive paths and [[close]] at lifecycle end.
  *
  * @param bootstrapServers Kafka bootstrap servers (comma-separated host:port)
  * @param clientId         Kafka client.id for metrics and broker logs
  */
final class KafkaProducerApi(
  bootstrapServers: String = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
  clientId:        String  = sys.env.getOrElse("KAFKA_CLIENT_ID",        "ib-producer")
) {
  private val props = new Properties()
  props.put("bootstrap.servers", bootstrapServers)
  props.put("client.id", clientId)
  props.put("acks", "all")
  props.put("key.serializer", classOf[StringSerializer].getName)
  props.put("value.serializer", classOf[StringSerializer].getName)
  props.put("linger.ms", sys.env.getOrElse("KAFKA_LINGER_MS", "5"))
  props.put("batch.size", sys.env.getOrElse("KAFKA_BATCH_SIZE", "32768"))

  private val producer = new KafkaProducer[String, String](props)

  /**
    * Send a record to Kafka asynchronously.
    *
    * @param topic target Kafka topic
    * @param key   record key (used for partitioning)
    * @param value record payload (JSON string recommended)
    * @return      a Future that completes with the record metadata on ack
    *
    * @note Uses the producer's async `send`; consider calling [[flush]] on shutdown.
    */
  def send(topic: String, key: String, value: String): Future[RecordMetadata] = {
    producer.send(new ProducerRecord[String, String](topic, key, value))
  }

  /** Force the producer to send all buffered records. */
  def flush(): Unit = producer.flush()

  /** Close the underlying KafkaProducer and release resources. */
  def close(): Unit = producer.close()
}

/**
  * Companion with a convenience constructor using environment defaults.
  */
object KafkaProducerApi {
  /** Create a producer using environment-driven defaults. */
  def apply(): KafkaProducerApi = new KafkaProducerApi()
}
