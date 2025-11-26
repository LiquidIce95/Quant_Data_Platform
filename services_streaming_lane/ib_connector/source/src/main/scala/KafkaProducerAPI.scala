package src.main.scala

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties
import java.util.concurrent.Future

class KafkaProducerApi(
  bootstrapServers: String = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
  clientId:        String  = sys.env.getOrElse("KAFKA_CLIENT_ID",        "ib-producer"),
  api: ApiHandler
) {
  private val props = new Properties()
  props.put("bootstrap.servers", bootstrapServers)
  props.put("client.id", clientId)
  props.put("acks", "all")
  props.put("key.serializer", classOf[StringSerializer].getName)
  props.put("value.serializer", classOf[StringSerializer].getName)
  props.put("linger.ms", sys.env.getOrElse("KAFKA_LINGER_MS", "100"))
  props.put("batch.size", sys.env.getOrElse("KAFKA_BATCH_SIZE", "32768"))

  private val producer = new KafkaProducer[String, String](props)

  val symbolUniverse = api.computeSymbolUniverse()

  /**
    * maps the conIds to the partiion index,
    * since the symbols are ordered ascending by expiry we will distribute
    * the hot months first
    */
  val partitionIndices : Map[Long,String] = {
    for (i<-0 until symbolUniverse.size) yield symbolUniverse(i)._1 -> i.toString()
  }.toMap

  /**
    * 
    *
    * @param topic the topic to send the messages to 
    * @param key the key that determines the partion, needs to be conId= contract id
    * @param value the json message
    * @return
    */
  def send(topic: String, key: Long, value: String): Future[RecordMetadata] = {
    producer.send(new ProducerRecord[String, String](topic, partitionIndices(key), value))
  }

  def flush(): Unit = producer.flush()
  def close(): Unit = producer.close()
}
