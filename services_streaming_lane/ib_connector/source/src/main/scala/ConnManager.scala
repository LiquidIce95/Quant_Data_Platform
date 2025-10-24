// File: src/main/scala/ConnManager.scala
package src.main.scala

import com.ib.client._
import java.util.concurrent.LinkedBlockingQueue

/**
  * Manages market data lifecycle on its own thread.
  * All EClientSocket calls are serialized through ClientIo (single writer).
  *
  * @param c   IB client socket (already connected)
  * @param io  single-writer lane that executes socket calls in-order
  * @param streamType "realtime" → TBT+L2, otherwise delayed L1 via reqMktData
  */
final class ConnManager(
	c: EClientSocket,
	io: ClientIo,
	streamType: String
) extends Runnable {

	private val EXCHANGE = "NYMEX"
	private val CURRENCY = "USD"
	private val MAX_BOOK_DEPTH: Int = 12

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

	/**
	  * Waits for discovery (Connections has entries), then requests all streams.
	  * If discovery empty, sleep 10s and retry; fail if still empty.
	  */
	def startStreams(): Unit = {
		work.put(new Runnable {
			def run(): Unit = {
				if (Connections.discoveryEmpty) {
					try Thread.sleep(10_000L) catch { case _: Throwable => () }
				}
				if (Connections.discoveryEmpty) {
					throw new IllegalStateException("[ConnManager] discovery not ready: Connections has no entries")
				}

				val entries = Connections.entriesSortedByReqId

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

						if (streamType == "realtime") {
							cc.reqTickByTickData(reqId, con, "AllLast", 0, false)
							cc.reqMktDepth(reqId, con, MAX_BOOK_DEPTH, false, null)
						} else {
							cc.reqMktData(reqId, con, "", false, false, null)
						}

						// status+state changes are guarded by Connections
						Connections.setStatus(ConnManager.this, code, isL2 = false, Connections.ConnStatus.ON)
						Connections.setStatus(ConnManager.this, code, isL2 = true,  Connections.ConnStatus.ON)
						Connections.setState(ConnManager.this, code, isL2 = false, ConnState.VALID)
						Connections.setState(ConnManager.this, code, isL2 = true,  ConnState.VALID)

						i += 1
					}
				}
			}
		})
	}

	/**
	  * Cancels all streams that were started via startStreams().
	  * Changes status to DROPPED and state to INVALID (via Connections guards).
	  */
	def cancelAll(): Unit = {
		work.put(new Runnable {
			def run(): Unit = {
				val entries = Connections.entriesSortedByReqId

				var i = 0
				while (i < entries.size) {
					val (reqId, code) = entries(i)

					io.submit(cc => cc.cancelTickByTickData(reqId))
					io.submit(cc => cc.cancelMktDepth(reqId, false))

					Connections.setStatus(ConnManager.this, code, isL2 = false, Connections.ConnStatus.DROPPED)
					Connections.setStatus(ConnManager.this, code, isL2 = true,  Connections.ConnStatus.DROPPED)
					Connections.setState(ConnManager.this, code, isL2 = false, ConnState.INVALID)
					Connections.setState(ConnManager.this, code, isL2 = true,  ConnState.INVALID)

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
}
