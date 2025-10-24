// File: src/main/scala/ConnManager.scala
package src.main.scala

import com.ib.client._
import java.util.concurrent.LinkedBlockingQueue

/**
  * Lifecycle manager (runs on its own thread).
  * Uses ClientIo to serialize socket calls.
  * Follows new rules: status flips manager-only; state INIT/INVALID → VALID manager-only.
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

	/** Wait for discovery then start streams; set status ON and state VALID (manager-only). */
	def startStreams(): Unit = {
		work.put(new Runnable {
			def run(): Unit = {
				if (Connections.discoveryEmpty) { try Thread.sleep(10_000L) catch { case _: Throwable => () } }
				if (Connections.discoveryEmpty) throw new IllegalStateException("discovery not ready")

				val entries = Connections.entriesSortedByReqId
				io.submit { cc =>
					cc.reqMarketDataType(if (streamType == "realtime") 1 else 3)
					var i = 0
					while (i < entries.size) {
						val (reqId, code) = entries(i)
						val con = new Contract
						con.secType("FUT"); con.exchange(EXCHANGE); con.currency(CURRENCY); con.localSymbol(code)

						if (streamType == "realtime") {
							cc.reqTickByTickData(reqId, con, "AllLast", 0, false)
							cc.reqMktDepth(reqId, con, MAX_BOOK_DEPTH, false, null)
						} else {
							cc.reqMktData(reqId, con, "", false, false, null)
						}

						Connections.setStatus(ConnManager.this, code, isL2=false, Connections.ON)
						Connections.setStatus(ConnManager.this, code, isL2=true,  Connections.ON)
						// manager can INIT/INVALID -> VALID
						Connections.setState(ConnManager.this, code, isL2=false, ConnState.VALID)
						Connections.setState(ConnManager.this, code, isL2=true,  ConnState.VALID)

						i += 1
					}
				}
			}
		})
	}

	/** Stop all streams; flip status to DROPPED (state left as-is; wrapper will invalidate on data if needed). */
	def dropAll(): Unit = {
		work.put(new Runnable {
			def run(): Unit = {
				val entries = Connections.entriesSortedByReqId
				var i = 0
				while (i < entries.size) {
					val (reqId, code) = entries(i)
					io.submit(cc => cc.cancelTickByTickData(reqId))
					io.submit(cc => cc.cancelMktDepth(reqId, false))
					Connections.setStatus(ConnManager.this, code, isL2=false, Connections.DROPPED)
					Connections.setStatus(ConnManager.this, code, isL2=true,  Connections.DROPPED)
					i += 1
				}
			}
		})
	}

	
}
