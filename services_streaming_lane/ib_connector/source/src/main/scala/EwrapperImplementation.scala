package src.main.scala

import com.ib.client._
import scala.util.control.NonFatal
import scala.collection.mutable

/**
  * Minimal EWrapper impl that forwards:
  *  - tick-by-tick "Last"   → topic "tickLast"
  *  - L2 depth (SMART)      → topic "L2_data"
  *
  * @param producer   Kafka producer wrapper
  * @param lookupCode function to resolve trading symbol for a given reqId (e.g., reqIdToCode.get)
  * @param topicTickLast Kafka topic for tick-by-tick "Last"
  * @param topicL2       Kafka topic for L2 book updates
  */
final class EwrapperImplementation(
  producer: KafkaProducerApi,
  lookupMap: mutable.Map[Int, String],
  topicTickLast: String = "ticklast",
  topicL2: String = "l2-data"
) extends DefaultEWrapper {

  private def codeFor(reqId: Int): String =
    lookupMap.getOrElse(reqId,"?")

  // ---- Tick-by-tick Last ----
  override def tickByTickAllLast(
    reqId: Int,
    tickType: Int,            // 0="Last", 1="AllLast"
    time: Long,
    price: Double,
    size: Decimal,
    tickAttribLast: TickAttribLast,
    exchange: String,
    specialConditions: String
  ): Unit = {
    try {
      val code = codeFor(reqId)
      val json = Transforms.tickLastJson(
        reqId        = reqId,
        tickType     = tickType,
        time         = time,
        price        = price,
        size         = size,
        attr         = tickAttribLast,
        exchange     = exchange,
        specialConditions = specialConditions,
        tradingSymbol = code
      )
      producer.send(topicTickLast, /*key*/ code, /*value*/ json)
    } catch {
      case NonFatal(e) =>
        System.err.println(s"[tickLast][reqId=$reqId] failed to produce: ${e.getMessage}")
    }
  }

  // ---- L2 depth (SMART) ----
  override def updateMktDepthL2(
    reqId: Int,
    position: Int,
    marketMaker: String,
    operation: Int,     // 0=insert,1=update,2=delete
    side: Int,          // 0=ask, 1=bid
    price: Double,
    size: Decimal,
    isSmartDepth: Boolean
  ): Unit = {
    try {
      val code = codeFor(reqId)
      val json = Transforms.l2Json(
        reqId         = reqId,
        position      = position,
        marketMaker   = marketMaker,
        operation     = operation,
        side          = side,
        price         = price,
        size          = size,
        isSmartDepth  = isSmartDepth,
        tradingSymbol = code
      )
      producer.send(topicL2, /*key*/ code, /*value*/ json)
    } catch {
      case NonFatal(e) =>
        System.err.println(s"[l2][reqId=$reqId] failed to produce: ${e.getMessage}")
    }
  }

  // ---- (optional) basic logging ----
  override def error(reqId: Int, time: Long, code: Int, msg: String, adv: String): Unit =
    System.err.println(s"[err] code=$code msg=$msg reqId=$reqId")

  override def marketDataType(reqId: Int, marketDataType: Int): Unit =
    println(s"[mdType][$reqId] $marketDataType (1=real,2=frozen,3=delayed,4=delayed-frozen)")

  override def connectionClosed(): Unit =
    println("[ib] connection closed (TwsApiImplementation)")
}
