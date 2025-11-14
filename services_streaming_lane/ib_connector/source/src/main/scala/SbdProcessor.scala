package src.main.scala
import upickle.core.LinkedHashMap
import ujson._

// will have states for each ConId of the smd topic and update those when new messges (string) arrive 
// its apply method will override onSmdFrame in StreamManager, sends request in form of conId to the requestSetSmd

class SbdProcessor(
    producerApi:KafkaProducerApi
    ) {

    val symbolUniverse :List[(Long,String)]= ApiHandler.computeSymbolUniverse()

    /**
      * The SbdProcessor will push / append requests here if a connection state is faulty
      * and its the only entity that can do that, and ConnectionManager the only entity 
      * that can pop request from the set
      * will contain conId to resubscribe the sbd topic
      * 
      */
    var requestSetSbd : Set[Long]=Set()

    
    /**
      * will overwrite the StreamManagers abstract member 'onSbdFrame'
      *
      * @param message raw json stirng
      */
    def apply(message: LinkedHashMap[String, ujson.Value]):Unit={}



}