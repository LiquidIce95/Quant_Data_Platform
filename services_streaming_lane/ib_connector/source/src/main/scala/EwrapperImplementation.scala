package src.main.scala

import com.ib.client._
import scala.util.control.NonFatal
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Try

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
  // ---- config ----
  private val HOST = "127.0.0.1"
  private val PORT = 4002            // 4002=GW paper, 7497=TWS paper
  private val CLIENT_ID = 1
  private val SYMBOL = "CL"
  private val SYMBOL_NG = "NG"
  private val EXCHANGE = "NYMEX"
  private val CURRENCY = "USD"
  private val REQ_CD = 1001
  private val REQ_SNAP = 5002
  private val REQ_STREAM = 5001

  val cdsCL = ListBuffer.empty[ContractDetails]
  val cdsNG = ListBuffer.empty[ContractDetails]

  @volatile var c: EClientSocket = null
  @volatile var sub = false
  @volatile var started = false
  var ends = 0

  def futQuery(sym: String): Contract = {
    val k = new Contract
    k.symbol(sym); k.secType("FUT"); k.exchange(EXCHANGE); k.currency(CURRENCY)
    k
  }

  // FIRST n unique months from the given buffer (no shadowing!)
  def fronts(n: Int, buf: ListBuffer[ContractDetails]): List[Contract] = {
    import scala.collection.mutable.{ListBuffer => LB, LinkedHashSet}
    val seen = LinkedHashSet.empty[String]
    val out  = LB.empty[Contract]
    buf.sortBy(cd => Try(cd.contract.lastTradeDateOrContractMonth.take(6).toInt).getOrElse(Int.MaxValue))
      .foreach { cd =>
        val yyyymm = Try(cd.contract.lastTradeDateOrContractMonth.take(6)).getOrElse("")
        if (yyyymm.nonEmpty && seen.add(yyyymm)) out += cd.contract
        if (out.size >= n) return out.toList
      }
    out.toList
  }
  
  override def connectAck(): Unit = { c.startAPI(); c.reqIds(-1) }

  private def codeFor(reqId: Int): String =
    lookupMap.getOrElse(reqId,"?")
  override def nextValidId(id: Int): Unit = {
    if (started) return
    started = true
    c.reqMarketDataType(3) // delayed

    c.reqContractDetails(REQ_CD,     futQuery(SYMBOL))
    println(s"[ib] reqContractDetails($REQ_CD) for $SYMBOL …")

    c.reqContractDetails(REQ_CD + 1, futQuery(SYMBOL_NG))
    println(s"[ib] reqContractDetails(${REQ_CD + 1}) for $SYMBOL_NG …")
  }

  // route each detail to the correct buffer by reqId
  override def contractDetails(r: Int, cd: ContractDetails): Unit = {
    if (r == REQ_CD) cdsCL += cd
    else if (r == REQ_CD + 1) cdsNG += cd
    else {
      // fallback based on symbol if ever needed
      if (cd.contract.symbol == SYMBOL) cdsCL += cd
      else if (cd.contract.symbol == SYMBOL_NG) cdsNG += cd
    }
  }

  // wait until BOTH details have ended, then subscribe once
  override def contractDetailsEnd(r: Int): Unit = if (!sub) {
    ends += 1
    if (ends < 2) return

    val xsCL = fronts(8, cdsCL)
    val xsNG = fronts(8, cdsNG)
    val xs   = xsCL ++ xsNG // CL first, then NG (deterministic)

    // implement sharding here

    if (xs.nonEmpty) {
      println("[ib] fronts: " + xs.map(k => s"${k.localSymbol} (${k.lastTradeDateOrContractMonth})").mkString(", "))
      c.reqMarketDataType(3)
      xs.zipWithIndex.foreach { case (con, i) =>
        val reqId = REQ_STREAM + i
        val code  = Option(con.localSymbol).filter(_.nonEmpty).getOrElse(con.symbol)
        lookupMap.update(reqId, code)
        c.reqMktData(reqId, con, "233", false, false, null) // RTVolume
        println(s"[ib] subscribed reqId=$reqId -> $code")
      }
      sub = true
    } else {
      System.err.println("[ib] no months found for CL/NG (permissions/exchange?)")
    }
  }
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
