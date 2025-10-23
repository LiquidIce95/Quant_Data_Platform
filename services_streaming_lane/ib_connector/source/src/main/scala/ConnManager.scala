// File: src/main/scala/ConnManager.scala
package src.main.scala

import com.ib.client._
import java.util.concurrent.LinkedBlockingQueue
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Try

/* Convention: (left=TickByTick, right=L2) */
final class ConnManager(
	c: EClientSocket,
	stateMap: mutable.Map[String,(ConnState,ConnState)],
	lookupMap: mutable.Map[Int, String],
	streamType: String,
	io: ClientIo
) extends Runnable {

	private val EXCHANGE = "NYMEX"
	private val CURRENCY = "USD"
	private val SYMBOL_CL = "CL"
	private val SYMBOL_NG = "NG"
	private val REQ_CD = 1001
	private val REQ_BASE = 5001
	private val MAX_BOOK_DEPTH: Int = 12

	private val cdsCL: ListBuffer[ContractDetails] = ListBuffer.empty[ContractDetails]
	private val cdsNG: ListBuffer[ContractDetails] = ListBuffer.empty[ContractDetails]
	private var detailsEnds: Int = 0
	private var started: Boolean = false
	private var codes: List[String] = Nil

	private val work = new LinkedBlockingQueue[Runnable]()
	@volatile private var running = false
	private var thread: Thread = null

	def start(): Unit = {
		if (running) return
		running = true
		thread = new Thread(this, "conn-manager")
		thread.setDaemon(true)
		thread.start()
	}

	def stop(): Unit = {
		running = false
		if (thread != null) thread.interrupt()
	}

	override def run(): Unit = {
		while (running) {
			val r = work.take()
			try r.run() catch { case _: Throwable => () }
		}
	}

	// ===== API called from EReader/Ewrapper thread; all enqueue to manager thread =====

	def init(): Unit = {
		work.put(new Runnable {
			def run(): Unit = {
				if (started) return
				started = true
				io.submit(cc => cc.reqContractDetails(REQ_CD,     futQuery(SYMBOL_CL)))
				io.submit(cc => cc.reqContractDetails(REQ_CD + 1, futQuery(SYMBOL_NG)))
			}
		})
	}

	def onContractDetails(r: Int, cd: ContractDetails): Unit = {
		work.put(new Runnable {
			def run(): Unit = {
				if (r == REQ_CD) cdsCL += cd
				else if (r == REQ_CD + 1) cdsNG += cd
			}
		})
	}

	def onContractDetailsEnd(r: Int): Unit = {
		work.put(new Runnable {
			def run(): Unit = {
				detailsEnds += 1
				if (detailsEnds < 2) return

				val xsCL = fronts(8, cdsCL)
				val xsNG = fronts(8, cdsNG)
				val xs = xsCL ++ xsNG

				codes = xs.map { con =>
					val ls = con.localSymbol
					if (ls != null && ls.nonEmpty) ls else con.symbol
				}

				io.submit { cc =>
					cc.reqMarketDataType(3)
					var i = 0
					while (i < xs.size) {
						val con = xs(i)
						val code = codes(i)
						val reqId = REQ_BASE + i

						lookupMap.update(reqId, code)
						if (!stateMap.contains(code)) stateMap.update(code, (new ConnState, new ConnState))

						if (streamType == "realtime") {
							cc.reqTickByTickData(reqId, con, "AllLast", 0, false)
							cc.reqMktDepth(reqId, con, MAX_BOOK_DEPTH, false, null)
						} else {
							cc.reqMktData(reqId, con, "233", false, false, null) // RTVolume
						}

						val st = stateMap(code)
						st._1.asManager -> ConnState.VALID
						st._2.asManager -> ConnState.VALID
						i += 1
					}
				}
			}
		})
	}

	def cancelAll(): Unit = {
		work.put(new Runnable {
			def run(): Unit = {
				var i = 0
				while (i < codes.size) {
					val reqId = REQ_BASE + i
					val code = codes(i)
					io.submit(cc => cc.cancelTickByTickData(reqId))
					io.submit(cc => cc.cancelMktDepth(reqId, false))
					val st = stateMap.getOrElse(code, (new ConnState, new ConnState))
					st._1.asManager -> ConnState.DROPPED
					st._2.asManager -> ConnState.DROPPED
					st._1.asManager -> ConnState.INVALID
					st._2.asManager -> ConnState.INVALID
					i += 1
				}
			}
		})
	}

	def restartDepth(reqId: Int, code: String): Unit = {
		work.put(new Runnable {
			def run(): Unit = {
				io.submit(cc => cc.cancelMktDepth(reqId, false))
				io.submitDelayed(2000L) { cc =>
					val con = new Contract
					con.secType("FUT"); con.exchange(EXCHANGE); con.currency(CURRENCY); con.localSymbol(code)
					cc.reqMktDepth(reqId, con, MAX_BOOK_DEPTH, false, null)
				}
			}
		})
	}

	// ===== helpers (manager thread only) =====

	private def futQuery(sym: String): Contract = {
		val k = new Contract
		k.symbol(sym); k.secType("FUT"); k.exchange(EXCHANGE); k.currency(CURRENCY)
		k
	}

	private def fronts(n: Int, buf: ListBuffer[ContractDetails]): List[Contract] = {
		val seen = new java.util.LinkedHashSet[String]()
		val out = new ListBuffer[Contract]()
		buf.sortBy(cd => Try(cd.contract.lastTradeDateOrContractMonth.take(6).toInt).getOrElse(Int.MaxValue))
			.foreach { cd =>
				val yyyymm = Try(cd.contract.lastTradeDateOrContractMonth.take(6)).getOrElse("")
				if (yyyymm.nonEmpty && !seen.contains(yyyymm)) {
					seen.add(yyyymm); out += cd.contract
					if (out.size >= n) return out.toList
				}
			}
		out.toList
	}
}
