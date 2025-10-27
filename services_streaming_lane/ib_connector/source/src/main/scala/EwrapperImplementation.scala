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
  *   - state=VALID & status=ON			-> process
  *   - state=VALID & status=DROPPED -> (L2) reset BookState, set INVALID (wrapper), skip
  *   - state=INVALID							 -> skip
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

	private def futQuery(sym: String): Contract = {
		val k = new Contract
		k.symbol(sym); k.secType("FUT"); k.exchange(EXCHANGE); k.currency(CURRENCY)
		k
	}

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

	private def codeFor(reqId: Int): String = Connections.lookupFor(reqId)

	// ---- discovery ----
	override def nextValidId(id: Int): Unit = { if (!started) started = true }

	override def contractDetails(r: Int, cd: ContractDetails): Unit = {
		if (r == REQ_CD) cdsCL += cd
		else if (r == REQ_CD + 1) cdsNG += cd
	}

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
	override def marketDataType(tid: Int, t: Int): Unit =
		println(s"[mdType] $t (1=real,2=frozen,3=delayed,4=delayed-frozen)")
	override def tickPrice(tid: Int, f: Int, p: Double, a: TickAttrib): Unit =
		println(s"[tickPrice][$tid][${Connections.lookupFor(tid)}] ${TickType.getField(f)} = $p")
	override def tickSize(tid: Int, f: Int, s: Decimal): Unit =
		println(s"[tickSize][$tid][${Connections.lookupFor(tid)}] ${TickType.getField(f)} = $s")
	override def tickString(tid: Int, f: Int, v: String): Unit =
		println(s"[tickString][$tid][${Connections.lookupFor(tid)}] ${TickType.getField(f)} = $v")

	override def error(reqId: Int, time: Long, code: Int, msg: String, adv: String): Unit =
		System.err.println(s"[err] code=$code msg=$msg reqId=$reqId")
}
