package src.main.scala

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties
import java.util.concurrent.Future

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

  def send(topic: String, key: String, value: String): Future[RecordMetadata] = {
    producer.send(new ProducerRecord[String, String](topic, key, value))
  }

  def flush(): Unit = producer.flush()
  def close(): Unit = producer.close()
}

object KafkaProducerApi {
  def apply(): KafkaProducerApi = new KafkaProducerApi()
}