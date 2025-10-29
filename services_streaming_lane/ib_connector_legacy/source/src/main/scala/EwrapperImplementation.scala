// File: src/main/scala/EwrapperImplementation.scala
package src.main.scala

import com.ib.client._
import scala.collection.mutable.ListBuffer
import scala.util.Try
import scala.util.control.NonFatal

/**
  * Discovery + data processing (no lifecycle).
  * Populates Connections on details end.
  *
  * Processing rules:
  *   - state=VALID & status=ON         -> process
  *   - state=VALID & status=DROPPED    -> (L2) reset BookState, set INVALID (wrapper), skip
  *   - state=INVALID                   -> skip
  *
  * Responsibilities:
  *   - Performs contract discovery for a fixed universe (CL, NG), selects front-month series,
  *     and registers (reqId ↔ code) in [[Connections]].
  *   - Consumes Tick-by-Tick (L1) and L2 callbacks, transforms them to JSON, and publishes via [[KafkaProducerApi]].
  *   - Maintains per-symbol in-memory L2 book state using [[BookState]] to apply INSERT/UPDATE/DELETE ops.
  *
  * Assumptions:
  *   - Only processes data when the corresponding (State=VALID, Status=ON).
  *   - On (State=VALID, Status=DROPPED) for L2, clears local book then flips state to INVALID.
  *   - Size for L2 may arrive as null; this is treated as zero.
  *
  * @param producer       Kafka producer (may be null in console-only scenarios)
  * @param topicTickLast  Kafka topic for Tick-by-Tick last trade events
  * @param topicL2        Kafka topic for L2 book row updates
  */
final class EwrapperImplementation(
	producer: KafkaProducerApi,
	topicTickLast: String = "ticklast",
	topicL2: String = "l2-data"
) extends DefaultEWrapper {

	@volatile var started = false

	private val EXCHANGE = "NYMEX"
	private val CURRENCY = "USD"
	private val SYMBOL_CL = "CL"
	private val SYMBOL_NG = "NG"
	private val REQ_CD = 1001
	private val REQ_BASE = 5001

	private val cdsCL = ListBuffer.empty[ContractDetails]
	private val cdsNG = ListBuffer.empty[ContractDetails]
	private var detailsEnds = 0

	private val MAX_BOOK_DEPTH: Int = 12
	private val BookStatesMap = scala.collection.mutable.HashMap.empty[String, BookState]

	/**
	  * Build a futures discovery query contract for the given root symbol.
	  *
	  * @param sym  root symbol (e.g., "CL", "NG")
	  * @return     discovery contract (no localSymbol; monthly series returned via details)
	  */
	private def futQuery(sym: String): Contract = {
		val k = new Contract
		k.symbol(sym); k.secType("FUT"); k.exchange(EXCHANGE); k.currency(CURRENCY)
		k
	}

	/**
	  * Select up to `n` front-month distinct-year-month futures contracts from a buffer of ContractDetails.
	  *
	  * Ordering:
	  *   - Sorts by parsed yyyymm (first 6 chars of lastTradeDateOrContractMonth).
	  *   - Deduplicates by year-month, preserving earliest occurrence per month.
	  *
	  * @param n    maximum number of distinct year-month contracts to return
	  * @param buf  source buffer filled via `contractDetails` callbacks
	  * @return     list of Contracts (at most n), possibly fewer if insufficient unique months exist
	  */
	private def fronts(n: Int, buf: ListBuffer[ContractDetails]): List[Contract] = {
		import scala.collection.mutable.{ListBuffer => LB, LinkedHashSet}
		val seen = LinkedHashSet.empty[String]
		val out	 = LB.empty[Contract]
		buf.sortBy(cd => Try(cd.contract.lastTradeDateOrContractMonth.take(6).toInt).getOrElse(Int.MaxValue))
			.foreach { cd =>
				val yyyymm = Try(cd.contract.lastTradeDateOrContractMonth.take(6)).getOrElse("")
				if (yyyymm.nonEmpty && seen.add(yyyymm)) out += cd.contract
				if (out.size >= n) return out.toList
			}
		out.toList
	}

	/**
	  * Resolve trading code (symbol) for a given request id via [[Connections]].
	  *
	  * @param reqId IB request id
	  * @return      trading symbol code or "?" if not found
	  */
	private def codeFor(reqId: Int): String = Connections.lookupFor(reqId)

	// ---- discovery ----

	/**
	  * IB callback: first usable id. Used here as a simple "API is alive" signal.
	  *
	  * @param id  next valid order id (unused for market data)
	  */
	override def nextValidId(id: Int): Unit = { if (!started) started = true }

	/**
	  * IB callback: contract details result row.
	  *
	  * Appends received details to the appropriate buffer based on request id.
	  *
	  * @param r   request id (REQ_CD for CL, REQ_CD+1 for NG)
	  * @param cd  contract details
	  */
	override def contractDetails(r: Int, cd: ContractDetails): Unit = {
		if (r == REQ_CD) cdsCL += cd
		else if (r == REQ_CD + 1) cdsNG += cd
	}

	/**
	  * IB callback: end of contract details for a request id.
	  *
	  * After both CL and NG complete, selects the first 8 front months of each,
	  * registers (reqId ↔ code) in [[Connections]], and ensures default entries.
	  *
	  * @param r  request id that finished (ignored beyond counting)
	  */
	override def contractDetailsEnd(r: Int): Unit = {
		detailsEnds += 1
		if (detailsEnds < 2) return
		val xsCL = fronts(8, cdsCL)
		val xsNG = fronts(8, cdsNG)
		val xs	 = xsCL ++ xsNG
		var i = 0
		while (i < xs.size) {
			val con	 = xs(i)
			val code = Option(con.localSymbol).filter(_.nonEmpty).getOrElse(con.symbol)
			Connections.putLookup(REQ_BASE + i, code)
			Connections.ensureEntry(code) // default INVALID + DROPPED
			i += 1
		}
	}

	// ---- tick-by-tick (L1) ----

	/**
	  * IB callback: Tick-by-Tick "AllLast".
	  *
	  * Processing:
	  *   - If (State=VALID, Status=ON): transform to JSON and publish to Kafka.
	  *   - If (State=VALID, Status=DROPPED): flip to INVALID (wrapper authority) to force restart.
	  *   - Else: ignore.
	  *
	  * @param reqId             request id
	  * @param tickType          IB tick subtype (0=Last, 1=AllLast, etc.)
	  * @param time              exchange-provided epoch seconds
	  * @param price             last trade price
	  * @param size              last trade size (may be null for some feeds)
	  * @param tickAttribLast    IB attributes (pastLimit, unreported, …) may be null
	  * @param exchange          source exchange string
	  * @param specialConditions additional flags from IB
	  */
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
			val s	= Connections.stateOf(code, isL2 = false)
			val st = Connections.statusOf(code, isL2 = false)

			if (s == ConnState.VALID && st == Connections.ON) {
				val json = Transforms.tickLastJson(reqId, tickType, time, price, size, tickAttribLast, exchange, specialConditions, code)
				if (producer != null) producer.send(topicTickLast, code, json)
			} else if (s == ConnState.VALID && st == Connections.DROPPED) {
				Connections.setState(this, code, isL2 = false, ConnState.INVALID)
			}
		} catch { case NonFatal(_) => () }
	}

	// ---- L2 ----

	/**
	  * IB callback: L2 market depth update with operation semantics.
	  *
	  * Processing:
	  *   - Only when (State=VALID, Status=ON) for the L2 leg.
	  *   - Applies operation to per-symbol [[BookState]]:
	  *       0 -> insert, 1 -> update, 2 -> delete (others ignored).
	  *   - Emits JSON rows for all changed levels and publishes to Kafka.
	  *   - If (State=VALID, Status=DROPPED): clears local book and flips state to INVALID.
	  *
	  * Assumptions:
	  *   - `size` may be null; treated as 0.0.
	  *   - Book invariants (monotonicity, no holes) are enforced by [[BookState]].
	  *
	  * @param reqId        request id
	  * @param position     depth level index (0..depth-1)
	  * @param marketMaker  market maker id (may be empty)
	  * @param operation    0=INSERT, 1=UPDATE, 2=DELETE
	  * @param side         0=ask, 1=bid
	  * @param price        price value (non-negative for insert/update)
	  * @param size         size as Decimal; may be null
	  * @param isSmartDepth whether SMART depth routing is used
	  */
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
			val s	= Connections.stateOf(code, isL2 = true)
			val st = Connections.statusOf(code, isL2 = true)

			if (s == ConnState.VALID && st == Connections.ON) {
				var newRows: List[(Int, Int, Double, Double, Long)] = Nil
				try {
					val book = BookStatesMap.getOrElseUpdate(code, new BookState(MAX_BOOK_DEPTH))
					val ts	= System.currentTimeMillis()
					val qty = if (size == null) 0.0 else {
						val s = size.toString; if (s == null || s.isEmpty) 0.0 else java.lang.Double.parseDouble(s)
					}
					newRows = operation match {
						case 0 => book.insert(side, position, price, qty, ts)
						case 1 => book.update(side, position, price, qty, ts)
						case 2 => book.delete(side, position, ts)
						case _ => Nil
					}
				} catch { case _: IllegalArgumentException => () }

				newRows.foreach { case (sSide, lvl, p, sz, tsUpd) =>
					val json = Transforms.l2Json(reqId, lvl, marketMaker, tsUpd, sSide, p, sz, isSmartDepth, code)
					producer.send(topicL2, code, json)
				}
			} else if (s == ConnState.VALID && st == Connections.DROPPED) {
				BookStatesMap.remove(code)
				Connections.setState(this, code, isL2 = true, ConnState.INVALID)
			}
		} catch { case NonFatal(_) => () }
	}

	// ---- console helpers for delayed tests ----

	/**
	  * Console helper: logs current market data type (1=real,2=frozen,3=delayed,4=delayed-frozen).
	  *
	  * @param tid request id (ignored)
	  * @param t   market data type code
	  */
	override def marketDataType(tid: Int, t: Int): Unit =
		println(s"[mdType] $t (1=real,2=frozen,3=delayed,4=delayed-frozen)")

	/**
	  * Console helper: prints tick price callback for delayed testing.
	  *
	  * @param tid request id
	  * @param f   tick field code
	  * @param p   price
	  * @param a   attributes (ignored)
	  */
	override def tickPrice(tid: Int, f: Int, p: Double, a: TickAttrib): Unit =
		println(s"[tickPrice][$tid][${Connections.lookupFor(tid)}] ${TickType.getField(f)} = $p")

	/**
	  * Console helper: prints tick size callback for delayed testing.
	  *
	  * @param tid request id
	  * @param f   tick field code
	  * @param s   size (Decimal)
	  */
	override def tickSize(tid: Int, f: Int, s: Decimal): Unit =
		println(s"[tickSize][$tid][${Connections.lookupFor(tid)}] ${TickType.getField(f)} = $s")

	/**
	  * Console helper: prints tick string callback for delayed testing.
	  *
	  * @param tid request id
	  * @param f   tick field code
	  * @param v   value
	  */
	override def tickString(tid: Int, f: Int, v: String): Unit =
		println(s"[tickString][$tid][${Connections.lookupFor(tid)}] ${TickType.getField(f)} = $v")

	/**
	  * IB callback: error reporting.
	  *
	  * Logs the error code and message to stderr with the associated reqId if present.
	  *
	  * @param reqId request id (may be -1)
	  * @param time  epoch seconds (if provided by IB)
	  * @param code  IB error code
	  * @param msg   human-readable error message
	  * @param adv   additional advisory text (may be empty)
	  */
	override def error(reqId: Int, time: Long, code: Int, msg: String, adv: String): Unit =
		System.err.println(s"[err] code=$code msg=$msg reqId=$reqId")
}
