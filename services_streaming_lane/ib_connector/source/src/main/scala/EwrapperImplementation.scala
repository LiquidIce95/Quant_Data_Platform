package src.main.scala

import com.ib.client._
import scala.util.control.NonFatal
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Try
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/**
  * Minimal EWrapper impl that forwards:
  *  - tick-by-tick "Last"   → topic "tickLast"
  *  - L2 depth (SMART)      → topic "L2_data"
  *
  * @param producer   Kafka producer wrapper
  * @param lookupMap  reqId → trading symbol mapping
  * @param topicTickLast Kafka topic for tick-by-tick "Last"
  * @param topicL2       Kafka topic for L2 book updates
  *
  * Also maintains an in-memory order book per symbol (BookStatesMap) to validate and
  * transform IB L2 operations into simple rows suitable for Kafka downstream consumers.
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

  // New: default max depth for all order books
  private val MAX_BOOK_DEPTH: Int = 12

  // New: contract code -> BookState
  private val BookStatesMap: mutable.HashMap[String, BookState] = mutable.HashMap.empty[String, BookState]

  val cdsCL = ListBuffer.empty[ContractDetails]
  val cdsNG = ListBuffer.empty[ContractDetails]

  @volatile var c: EClientSocket = null
  @volatile var sub = false
  @volatile var started = false
  var ends = 0

  /** Build a generic FUT contract query.
    *
    * @param sym root symbol, e.g. "CL" or "NG"
    * @return Contract with secType=FUT, exchange/currency set from config.
    *
    * Used to request contract details to enumerate front-month futures.
    */
  def futQuery(sym: String): Contract = {
    val k = new Contract
    k.symbol(sym); k.secType("FUT"); k.exchange(EXCHANGE); k.currency(CURRENCY)
    k
  }

  /** Extract the first N unique contract months from a buffer of ContractDetails.
    *
    * @param n   number of unique months desired
    * @param buf collected contract details from IB callbacks
    * @return List of Contracts ordered by ascending yyyymm, deduplicated by month.
    *
    * This ensures we subscribe deterministically to a small set of near-dated futures.
    */
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

  /** Restart a single market-depth stream for a given reqId.
    *
    * Why: If our in-memory book invariants fail (missing ops or corruption), we resubscribe
    *      the specific depth stream to rebuild a clean book with fresh incremental updates.
    *
    * Steps:
    *  1) cancel existing depth with the same reqId
    *  2) wait ~2s to let TWS/GW settle
    *  3) resubscribe to market depth using same reqId and localSymbol
    *
    * @param reqId IB stream id to restart
    */
  def restartStream(reqId: Int): Unit = {
    try {
      val code = codeFor(reqId)
      require(code != "?", s"unknown code for reqId=$reqId")

      
      c.cancelMktDepth(reqId,false)
 
      Thread.sleep(2000L)

      // Rebuild a contract using localSymbol (code) and same venue settings
      val con = new Contract
      con.secType("FUT")
      con.exchange(EXCHANGE)
      con.currency(CURRENCY)
      con.localSymbol(code)

      // Resubscribe to market depth using the configured depth
      c.reqMktDepth(reqId,con,MAX_BOOK_DEPTH,false,null)
      println(s"[ib] restarted stream reqId=$reqId -> $code")
    } catch {
      case NonFatal(e) =>
        System.err.println(s"[restart][$reqId] failed: ${e.getMessage}")
    }
  }

  /** IB callback acknowledging the socket connection; kicks off id allocation. */
  override def connectAck(): Unit = { c.startAPI(); c.reqIds(-1) }

  /** Resolve trading symbol for a given reqId.
    *
    * @param reqId subscription id
    * @return mapped symbol or "?" if not found
    */
  private def codeFor(reqId: Int): String =
    lookupMap.getOrElse(reqId,"?")

  /** First valid id allocation from IB; issues initial contract detail requests once. */
  override def nextValidId(id: Int): Unit = {
    if (started) return
    started = true
    c.reqMarketDataType(3) // delayed

    c.reqContractDetails(REQ_CD,     futQuery(SYMBOL))
    println(s"[ib] reqContractDetails($REQ_CD) for $SYMBOL …")

    c.reqContractDetails(REQ_CD + 1, futQuery(SYMBOL_NG))
    println(s"[ib] reqContractDetails(${REQ_CD + 1}) for $SYMBOL_NG …")
  }

  /** Collect contract details; routes entries to CL/NG buffers based on reqId/symbol. */
  override def contractDetails(r: Int, cd: ContractDetails): Unit = {
    if (r == REQ_CD) cdsCL += cd
    else if (r == REQ_CD + 1) cdsNG += cd
    else {
      // fallback based on symbol if ever needed
      if (cd.contract.symbol == SYMBOL) cdsCL += cd
      else if (cd.contract.symbol == SYMBOL_NG) cdsNG += cd
    }
  }

  /** After both details streams end, compute front months, initialize books, and subscribe.
    *
    * - Creates BookState per code (if absent).
    * - Subscribes both tick-by-tick last and market depth with same reqIds.
    */
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
        // New: initialize BookState for this contract code
        if (!BookStatesMap.contains(code)) {
          BookStatesMap.update(code, new BookState(MAX_BOOK_DEPTH))
        }
        c.reqTickByTickData(reqId,con,"AllLast",0,false)
        c.reqMktDepth(reqId,con,MAX_BOOK_DEPTH,false,null)
        println(s"[ib] subscribed reqId=$reqId -> $code")
      }
      sub = true
    } else {
      System.err.println("[ib] no months found for CL/NG (permissions/exchange?)")
    }
  }

  /** Tick-by-tick last trade callback → serialize and publish to Kafka.
    *
    * @param reqId stream id
    * @param tickType 0="Last", 1="AllLast"
    * @param time IB epoch seconds
    * @param price last price
    * @param size IB Decimal size (may be null)
    * @param tickAttribLast IB flags
    * @param exchange venue
    * @param specialConditions IB string with extra flags
    */
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

  /** L2 (SMART) depth callback:
    *
    * - Applies the IB operation (insert/update/delete) to our in-memory BookState.
    * - On invariant failure, logs and **asynchronously** restarts only this stream (reqId).
    * - Emits one Kafka message per changed book row using the simple L2 JSON format.
    *
    * @param reqId        stream id
    * @param position     book level index from IB (0-based)
    * @param marketMaker  MM code (opaque)
    * @param operation    0=insert, 1=update, 2=delete
    * @param side         0=ask, 1=bid
    * @param price        level price from IB
    * @param size         level size (IB Decimal, may be null)
    * @param isSmartDepth IB smart-depth flag
    */
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
      var new_l2_data: List[(Int, Int, Double, Double, Long)] = Nil
      // New: apply operation to our in-memory book state (must already exist)
      try {
        val book = BookStatesMap.get(code).getOrElse {
          throw new IllegalStateException(s"No BookState initialized for code=$code")
        }
        val ts  = System.currentTimeMillis()
        val qty = if (size == null) 0.0 else {
          val s = size.toString
          if (s == null || s.isEmpty) 0.0 else java.lang.Double.parseDouble(s)
        }

        new_l2_data = operation match {
          case 0 => book.insert(side, position, price, qty, ts)
          case 1 => book.update(side, position, price, qty, ts)
          case 2 => book.delete(side, position, ts)
          case _ => throw new IllegalStateException(s"Ib send an unown operation for code=$code") // ignore unknown ops
        }
      } catch {
        case _: IllegalArgumentException =>
          // Invariant violation or bad args in BookState -> signal need to restart
          System.err.println(s"oops we need to restart stream for $code")
          Future { restartStream(reqId) }(ExecutionContext.global)
        case NonFatal(e) =>
          System.err.println(s"[book][$code] unexpected failure: ${e.getMessage}")
      }

      // Emit one Kafka message per changed row in new_l2_data using the new formatter
      new_l2_data.foreach { case (s, lvl, p, sz, tsUpd) =>
        val json = Transforms.l2Json(
          reqId         = reqId,
          position      = lvl,
          marketMaker   = marketMaker,
          update_time   = tsUpd,
          side          = s,
          price         = p,
          size          = sz,
          isSmartDepth  = isSmartDepth,
          tradingSymbol = code
        )
        producer.send(topicL2, code, json)
      }
    } catch {
      case NonFatal(e) =>
        System.err.println(s"[l2][reqId=$reqId] failed to produce: ${e.getMessage}")
    }
  }

  /** IB error callback: logs code/message and affected reqId. */
  override def error(reqId: Int, time: Long, code: Int, msg: String, adv: String): Unit =
    System.err.println(s"[err] code=$code msg=$msg reqId=$reqId")

  /** Market data type changes: 1=real, 2=frozen, 3=delayed, 4=delayed-frozen. */
  override def marketDataType(reqId: Int, marketDataType: Int): Unit =
    println(s"[mdType][$reqId] $marketDataType (1=real,2=frozen,3=delayed,4=delayed-frozen)")

  /** Connection closed notification. */
  override def connectionClosed(): Unit =
    println("[ib] connection closed (TwsApiImplementation)")
}
