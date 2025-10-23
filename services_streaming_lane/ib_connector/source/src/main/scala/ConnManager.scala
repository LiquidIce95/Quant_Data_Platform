// File: src/main/scala/ConnManager.scala
package src.main.scala

import com.ib.client._
import java.util.concurrent.LinkedBlockingQueue
import scala.collection.mutable

/**
  * Manages the lifecycle of market data streams on a dedicated thread.
  * All IB socket calls are funneled through a single-writer IO lane (ClientIo)
  * to avoid concurrency issues with EClientSocket.
  *
  * @param c          IB client socket (must be connected by the time streams start)
  * @param stateMap   shared map: tradingSymbol → (ConnState for TickByTick, ConnState for L2)
  * @param lookupMap  shared map: reqId → tradingSymbol (populated by the EWrapper during discovery)
  * @param streamType "realtime" to request TBT+L2, any other value requests delayed L1 via reqMktData
  * @param io         single-writer lane that serializes all calls into EClientSocket
  */
final class ConnManager(
	c: EClientSocket,
	stateMap: mutable.Map[String,(ConnState,ConnState)],
	lookupMap: mutable.Map[Int, String],
	streamType: String,
	io: ClientIo
) extends Runnable {

	// ---- simple config ----
	private val EXCHANGE = "NYMEX"
	private val CURRENCY = "USD"
	private val REQ_BASE = 5001
	private val MAX_BOOK_DEPTH: Int = 12

	// ---- manager thread ----
	private val work = new LinkedBlockingQueue[Runnable]()
	@volatile private var running = false
	private var thread: Thread = null

	/**
	  * Start the ConnManager thread (idempotent).
	  */
	def start(): Unit = {
		if (running) return
		running = true
		thread = new Thread(this, "conn-manager")
		thread.setDaemon(true)
		thread.start()
	}

	/**
	  * Stop the ConnManager thread (best-effort).
	  */
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

	/**
	  * Wait for discovery to populate lookupMap/stateMap, then request all streams.
	  * Rules (stupid simple):
	  *  - If lookupMap or stateMap are empty, wait 10 seconds and retry once.
	  *  - If still empty on second check, throw an error (IllegalStateException).
	  *
	  * IB socket calls are issued via the IO lane (ClientIo).
	  */
	def startStreams(): Unit = {
		work.put(new Runnable {
			def run(): Unit = {
				// first check
				println("start STreams called")
				if (lookupMap.isEmpty || stateMap.isEmpty) {
					try Thread.sleep(10_000L) catch { case _: Throwable => () }
				}
				// second (final) check
				if (lookupMap.isEmpty || stateMap.isEmpty) {
					throw new IllegalStateException("[ConnManager] discovery not ready: lookupMap/stateMap are empty")
				}
				println("maps are not empty")
				// sort by reqId for determinism
				val entries = lookupMap.toSeq.sortBy(_._1) // (reqId, code)

				io.submit { cc =>
					cc.reqMarketDataType(if (streamType == "realtime") 1 else 3)

					var i = 0
					while (i < entries.size) {
						val (reqId, code) = entries(i)

						val con = new Contract
						con.secType("FUT")
						con.exchange(EXCHANGE)
						con.currency(CURRENCY)
						con.localSymbol(code)

						// ensure ConnState tuple exists (INIT by default)
						if (!stateMap.contains(code)) {
							stateMap.update(code, (new ConnState, new ConnState))
						}
						val st = stateMap(code)

						if (streamType == "realtime") {
							cc.reqTickByTickData(reqId, con, "AllLast", 0, false)
							cc.reqMktDepth(reqId, con, MAX_BOOK_DEPTH, false, null)
						} else {
							// delayed L1 top-of-book (tickPrice/tickSize/tickString)
							cc.reqMktData(reqId, con, "233", false, false, null)
						}

						// mark both connections VALID (manager-only transition)
						st._1.asManager -> ConnState.VALID
						st._2.asManager -> ConnState.VALID

						i += 1
					}
				}
			}
		})
	}

	/**
	  * Cancel all streams previously started via startStreams().
	  * IB calls are serialized via the IO lane.
	  */
	def cancelAll(): Unit = {
		work.put(new Runnable {
			def run(): Unit = {
				val entries = lookupMap.toSeq.sortBy(_._1)

				var i = 0
				while (i < entries.size) {
					val (reqId, code) = entries(i)

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

	/**
	  * Restart a single market-depth stream (helper).
	  *
	  * @param reqId stream id to restart
	  * @param code  trading symbol (localSymbol) for the contract
	  */
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
}
