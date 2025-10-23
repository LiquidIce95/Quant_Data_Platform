// File: src/main/scala/EwrapperImplementation.scala
package src.main.scala

import com.ib.client._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Try
import scala.util.control.NonFatal

/**
  * Minimal EWrapper that:
  *  - performs **discovery only** (reqContractDetails is triggered from main),
  *  - builds the universe of codes,
  *  - populates shared lookupMap (reqId → code) and stateMap (code → (ConnState, ConnState)),
  *  - does **not** start/cancel streams (ConnManager owns lifecycle).
  *
  * For delayed data tests, also prints L1 ticks (tickPrice/tickSize/tickString).
  *
  * @param producer   Kafka producer wrapper (may be null for console-only runs)
  * @param lookupMap  shared reqId → trading symbol map (populated here)
  * @param topicTickLast Kafka topic for tick-by-tick "Last" (used if producer != null)
  * @param topicL2       Kafka topic for L2 (used if producer != null)
  */
final class EwrapperImplementation(
	producer: KafkaProducerApi,
	lookupMap: mutable.Map[Int, String],
	stateMap: mutable.Map[String,(ConnState,ConnState)],
	topicTickLast: String = "ticklast",
	topicL2: String = "l2-data"
) extends DefaultEWrapper {

	@volatile var started = false

	// ---- discovery config ----
	private val EXCHANGE = "NYMEX"
	private val CURRENCY = "USD"
	private val SYMBOL_CL = "CL"
	private val SYMBOL_NG = "NG"
	private val REQ_CD = 1001
	private val REQ_BASE = 5001

	// buffers for contract discovery
	private val cdsCL = ListBuffer.empty[ContractDetails]
	private val cdsNG = ListBuffer.empty[ContractDetails]
	private var detailsEnds = 0

	// optional: book state for realtime L2 path (kept for compatibility)
	private val MAX_BOOK_DEPTH: Int = 12
	private val BookStatesMap: mutable.HashMap[String, BookState] = mutable.HashMap.empty[String, BookState]

	/** Resolve trading symbol for a given reqId (for printing / producing). */
	private def codeFor(reqId: Int): String =
		lookupMap.getOrElse(reqId, "?")

	/** Build a simple FUT contract query. */
	private def futQuery(sym: String): Contract = {
		val k = new Contract
		k.symbol(sym); k.secType("FUT"); k.exchange(EXCHANGE); k.currency(CURRENCY)
		k
	}

	/** FIRST n unique months from the given buffer (sorted by yyyymm). */
	private def fronts(n: Int, buf: ListBuffer[ContractDetails]): List[Contract] = {
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

	// ---- discovery callbacks (no req*/cancel* here) ----

	override def nextValidId(id: Int): Unit = {
		if (started) return
		started = true
		// No-op: main will issue reqMarketDataType + reqContractDetails.
		// Keeping this override to guard multiple-start semantics.
	}

	override def contractDetails(r: Int, cd: ContractDetails): Unit = {
		if (r == REQ_CD) cdsCL += cd
		else if (r == REQ_CD + 1) cdsNG += cd
	}

	override def contractDetailsEnd(r: Int): Unit = {
		detailsEnds += 1
		if (detailsEnds < 2) return

		val xsCL = fronts(8, cdsCL)
		val xsNG = fronts(8, cdsNG)
		val xs   = xsCL ++ xsNG

		// Populate reqId → code; leave ConnState in INIT (manager will set VALID on subscribe)
		var i = 0
		while (i < xs.size) {
			val con  = xs(i)
			val code = Option(con.localSymbol).filter(_.nonEmpty).getOrElse(con.symbol)
			lookupMap.update(REQ_BASE + i, code)
			stateMap.update(code,(new ConnState(), new ConnState()))
			i += 1
		}
	}

	// ---- tick forwarding/printing (useful for delayed L1 tests) ----

	override def marketDataType(tid: Int, t: Int): Unit =
		println(s"[mdType] $t (1=real,2=frozen,3=delayed,4=delayed-frozen)")

	override def tickPrice(tid: Int, f: Int, p: Double, a: TickAttrib): Unit =
		println(s"[tickPrice][$tid][${lookupMap.getOrElse(tid, "?")}] ${TickType.getField(f)} = $p")

	override def tickSize(tid: Int, f: Int, s: Decimal): Unit =
		println(s"[tickSize][$tid][${lookupMap.getOrElse(tid, "?")}] ${TickType.getField(f)} = $s")

	override def tickString(tid: Int, f: Int, v: String): Unit =
		println(s"[tickString][$tid][${lookupMap.getOrElse(tid, "?")}] ${TickType.getField(f)} = $v")

	// ---- optional L2 path (kept unchanged; only active if realtime L2 requested) ----

	override def tickByTickAllLast(
		reqId: Int,
		tickType: Int,
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
				reqId, tickType, time, price, size, tickAttribLast, exchange, specialConditions, code
			)
			if (producer != null) producer.send(topicTickLast, code, json)
		} catch { case NonFatal(_) => () }
	}

	override def updateMktDepthL2(
		reqId: Int,
		position: Int,
		marketMaker: String,
		operation: Int,
		side: Int,
		price: Double,
		size: Decimal,
		isSmartDepth: Boolean
	): Unit = {
		try {
			val code = codeFor(reqId)
			var new_l2_data: List[(Int, Int, Double, Double, Long)] = Nil
			try {
				val book = BookStatesMap.getOrElseUpdate(code, new BookState(MAX_BOOK_DEPTH))
				val ts  = System.currentTimeMillis()
				val qty = if (size == null) 0.0 else {
					val s = size.toString; if (s == null || s.isEmpty) 0.0 else java.lang.Double.parseDouble(s)
				}
				new_l2_data = operation match {
					case 0 => book.insert(side, position, price, qty, ts)
					case 1 => book.update(side, position, price, qty, ts)
					case 2 => book.delete(side, position, ts)
					case _ => Nil
				}
			} catch { case _: IllegalArgumentException => () }

			new_l2_data.foreach { case (s, lvl, p, sz, tsUpd) =>
				val json = Transforms.l2Json(
					reqId, lvl, marketMaker, tsUpd, s, p, sz, isSmartDepth, code
				)
				if (producer != null) producer.send(topicL2, code, json)
			}
		} catch { case NonFatal(_) => () }
	}

	override def error(reqId: Int, time: Long, code: Int, msg: String, adv: String): Unit =
		System.err.println(s"[err] code=$code msg=$msg reqId=$reqId")
}
