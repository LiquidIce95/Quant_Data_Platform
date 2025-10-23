// File: src/main/scala/EwrapperImplementation.scala
package src.main.scala

import com.ib.client._
import scala.util.control.NonFatal
import scala.collection.mutable
import scala.collection.mutable.ListBuffer



final class EwrapperImplementation(
	producer: KafkaProducerApi,
	lookupMap: mutable.Map[Int, String],
	topicTickLast: String = "ticklast",
	topicL2: String = "l2-data"
) extends DefaultEWrapper {

	@volatile var started = false
	private var connManRef: ConnManager = null

	def setConnManager(cm: ConnManager): Unit = { connManRef = cm }

	private val MAX_BOOK_DEPTH: Int = 12
	private val BookStatesMap: mutable.HashMap[String, BookState] = mutable.HashMap.empty[String, BookState]

	private def codeFor(reqId: Int): String = lookupMap.getOrElse(reqId, "?")

	override def nextValidId(id: Int): Unit = {
		if (started) return
		started = true
		val cm = connManRef
		if (cm != null) {
			println("cm.init called")
			cm.init()
		}
	}

	override def contractDetails(r: Int, cd: ContractDetails): Unit = {
		val cm = connManRef
		if (cm != null) cm.onContractDetails(r, cd)
	}

	override def contractDetailsEnd(r: Int): Unit = {
		val cm = connManRef
		if (cm != null) cm.onContractDetailsEnd(r)
	}

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
	// override these functions to test with delayed market data if the setup works
    override def marketDataType(tid: Int, t: Int): Unit =
    	println(s"[mdType] $t (1=real,2=frozen,3=delayed,4=delayed-frozen)")

    override def tickPrice(tid: Int, f: Int, p: Double, a: TickAttrib): Unit =
        println(s"[tickPrice][$tid][${lookupMap.getOrElse(tid, "?")}] ${TickType.getField(f)} = $p")
    override def tickSize(tid: Int, f: Int, s: Decimal): Unit =
        println(s"[tickSize][$tid][${lookupMap.getOrElse(tid, "?")}] ${TickType.getField(f)} = $s")
    override def tickString(tid: Int, f: Int, v: String): Unit =
        println(s"[tickString][$tid][${lookupMap.getOrElse(tid, "?")}] ${TickType.getField(f)} = $v")

	override def error(reqId: Int, time: Long, code: Int, msg: String, adv: String): Unit =
		System.err.println(s"[err] code=$code msg=$msg reqId=$reqId")
}
