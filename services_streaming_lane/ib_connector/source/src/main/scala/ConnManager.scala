// File: src/main/scala/ConnManager.scala
package src.main.scala

import com.ib.client._
import java.util.concurrent.LinkedBlockingQueue

/**
  * Lifecycle manager (runs on its own thread).
  * Uses ClientIo to serialize socket calls.
  *
  * State/Status rules with no INIT:
  *   - default state = INVALID
  *   - default status = DROPPED
  *   - startStreams(pollingInterval): periodic supervisor
  *       * cancels feeds for legs that are INVALID (per-leg)
  *       * recomputes pod shard
  *       * ON for in-shard, DROPPED for out-of-shard
  *       * transitions INVALID -> VALID (manager) and (re)starts only that leg
  *   - dropAll(): set status DROPPED (state left as-is)
  */
final class ConnManager(
	c: EClientSocket,
	io: ClientIo,
	streamType: String,
	shardingAlgorithm: (List[String] => Map[String, List[(Int, String)]]) = null,
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

	// --------------------------------------------------------------------------
	// Helpers
	// --------------------------------------------------------------------------

	/** Build IB contract for a given local symbol code. */
	private def mkContract(code: String): Contract = {
		val con = new Contract
		con.secType("FUT")
		con.exchange(EXCHANGE)
		con.currency(CURRENCY)
		con.localSymbol(code)
		con
	}

	/** Start only the TBT leg for (reqId, code). */
	private def startFeedTbt(cc: EClientSocket, reqId: Int, code: String): Unit = {
		val con = mkContract(code)
		if (streamType == "realtime") {
			cc.reqTickByTickData(reqId, con, "AllLast", 0, false)
		} else {
			cc.reqMktData(reqId, con, "", false, false, null)
		}
	}

	/** Start only the L2 leg for (reqId, code). No-op for non-realtime. */
	private def startFeedL2(cc: EClientSocket, reqId: Int, code: String): Unit = {
		if (streamType == "realtime") {
			val con = mkContract(code)
			cc.reqMktDepth(reqId, con, MAX_BOOK_DEPTH, false, null)
		}
	}

	/** Cancel only the TBT leg (safe even if not active). */
	private def cancelFeedTbt(cc: EClientSocket, reqId: Int): Unit = {
		cc.cancelTickByTickData(reqId)
	}

	/** Cancel only the L2 leg (safe even if not active). */
	private def cancelFeedL2(cc: EClientSocket, reqId: Int): Unit = {
		cc.cancelMktDepth(reqId, false)
	}

	/** If distributed=false, return whole symbol universe; else (later) compute shard. */
	private def computeSymbolsShardThisPod(): List[(Int, String)] = {
		if (shardingAlgorithm==null) Connections.entriesSortedByReqId.toList
		else {
			// TODO: use K8s API to compute shard for this pod
			Connections.entriesSortedByReqId.toList
		}
	}

	// --------------------------------------------------------------------------
	// API
	// --------------------------------------------------------------------------

	/**
	  * Periodic supervisor loop (per-leg control).
	  *
	  * At time t:
	  *  1) For every symbol/leg that is INVALID, cancel that leg (wrapper resets bookstate).
	  *  2) Recompute podSymbols (shard).
	  *  3) If in shard: set both legs' status=ON; for each INVALID leg -> set VALID and start only that leg.
	  *     If out of shard: set both legs' status=DROPPED (do not cancel valid legs now).
	  *
	  * @param pollingInterval milliseconds between iterations
	  */
	def startStreams(pollingInterval: Long): Unit = {
		work.put(new Runnable {
			def run(): Unit = {
				if (Connections.discoveryEmpty) { try Thread.sleep(10_000L) catch { case _: Throwable => () } }
				if (Connections.discoveryEmpty) throw new IllegalStateException("discovery not ready")

				// set market data type once; IB treats as idempotent
				io.submit { cc =>
					cc.reqMarketDataType(if (streamType == "realtime") 1 else 3)
				}

				while (running) {
					try {
						println("next polling running")
						val symbolUniverse: List[(Int, String)] = Connections.entriesSortedByReqId.toList

						// 1) per-leg cancel for INVALID
						io.submit { cc =>
							var i = 0
							while (i < symbolUniverse.size) {
								val (reqId, code) = symbolUniverse(i)
								val tbtState = Connections.stateOf(code, isL2 = false)
								val l2State = Connections.stateOf(code, isL2 = true)
								if (tbtState == ConnState.INVALID) cancelFeedTbt(cc, reqId)
								if (l2State == ConnState.INVALID) cancelFeedL2(cc, reqId)
								i += 1
							}
						}

						// 2) recompute shard
						val podSymbols: Set[String] = computeSymbolsShardThisPod().map(_._2).toSet

						// 3) per-leg apply status/state logic
						io.submit { cc =>
							var i = 0
							while (i < symbolUniverse.size) {
								val (reqId, code) = symbolUniverse(i)
								val inShard = podSymbols.contains(code)

								if (inShard) {
									// Status ON for both legs
									Connections.setStatus(ConnManager.this, code, isL2 = false, Connections.ON)
									Connections.setStatus(ConnManager.this, code, isL2 = true, Connections.ON)

									// If a leg is INVALID, flip to VALID and start only that leg
									val tbtState = Connections.stateOf(code, isL2 = false)
									if (tbtState == ConnState.INVALID) {
										Connections.setState(ConnManager.this, code, isL2 = false, ConnState.VALID)
										startFeedTbt(cc, reqId, code)
									}

									val l2State = Connections.stateOf(code, isL2 = true)
									if (l2State == ConnState.INVALID) {
										Connections.setState(ConnManager.this, code, isL2 = true, ConnState.VALID)
										startFeedL2(cc, reqId, code)
									}
									// VALID & ON legs: nothing else to do
								} else {
									// Out of shard: DROPPED for both; do not force-cancel valid legs (Case 3)
									Connections.setStatus(ConnManager.this, code, isL2 = false, Connections.DROPPED)
									Connections.setStatus(ConnManager.this, code, isL2 = true, Connections.DROPPED)
									// Case 1 covered by step (1) where INVALID legs were canceled already
								}
								i += 1
							}
						}

						try Thread.sleep(math.max(1L, pollingInterval)) catch { case _: Throwable => () }

					} catch {
						case _: InterruptedException => 
						case _: Throwable => ()
					}
				}
			}
		})
	}

	/** Stop all streams; flip status to DROPPED (state left as-is). */
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
					Connections.setStatus(ConnManager.this, code, isL2=true, Connections.DROPPED)
					i += 1
				}
			}
		})
	}
}
