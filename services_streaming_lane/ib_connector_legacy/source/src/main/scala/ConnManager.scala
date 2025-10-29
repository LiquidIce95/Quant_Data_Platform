// File: src/main/scala/ConnManager.scala
package src.main.scala

import com.ib.client._
import java.util.concurrent.LinkedBlockingQueue
import scala.jdk.CollectionConverters._
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientBuilder}


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
  *
  * Responsibilities and assumptions:
  *   - All EClientSocket invocations are funneled through the provided ClientIo to guarantee FIFO, single-threaded execution.
  *   - Sharding is optional; when provided, this manager discovers peer pods and computes per-pod symbol assignments.
  *   - This class never blocks the EWrapper callback thread; it operates via its own worker thread and queued tasks.
  *
  * @param c the IB client socket used for (un)subscribing market data; must be already constructed
  * @param io single-threaded I/O helper that serializes all socket operations
  * @param streamType stream mode selector: "realtime" uses tick-by-tick & L2; any other value uses delayed top-of-book only
  * @param shardingAlgorithm optional function that takes (peerPodNames, previousSharding) and returns the next sharding map;
  *                          when null, all discovered symbols are assigned to this pod
  */
final class ConnManager(
	c: EClientSocket,
	io: ClientIo,
	streamType: String,
	shardingAlgorithm: ((List[String],Map[String, List[(Int, String)]]) => Map[String, List[(Int, String)]]) = null,
) extends Runnable {

	private val EXCHANGE = "NYMEX"
	private val CURRENCY = "USD"
	private val MAX_BOOK_DEPTH: Int = 12

	private val work = new LinkedBlockingQueue[Runnable]()
	@volatile private var running = false
	private var thread: Thread = null
	private var currentSharding : Map[String,List[(Int,String)]] = null
	private val kubernetesClient = new KubernetesClientBuilder().build()

	/**
	  * Starts the manager's worker thread if not already running.
	  *
	  * Creates a daemon thread named "conn-manager" which drains the internal queue
	  * and processes lifecycle tasks. Idempotent if already started.
	  */
	def start(): Unit = {
		if (running) return
		running = true
		thread = new Thread(this, "conn-manager")
		thread.setDaemon(true)
		thread.start()
	}

	/**
	  * Stops the manager and interrupts its worker thread.
	  *
	  * Sets the running flag to false and interrupts the thread (if present).
	  * Pending tasks in the internal queue are not drained after stop.
	  */
	def stop(): Unit = {
		running = false
		if (thread != null) thread.interrupt()
	}

	/**
	  * Worker loop: processes Runnable tasks submitted to the internal queue.
	  *
	  * This method should only be executed by the manager's own thread.
	  * It catches and suppresses any Throwable from task execution to keep the loop alive.
	  */
	override def run(): Unit = {
		while (running) {
			val r = work.take()
			try r.run() catch { case _: Throwable => () }
		}
	}

	// --------------------------------------------------------------------------
	// Helpers
	// --------------------------------------------------------------------------

	/** 
	  * Build an IB futures contract for a given local symbol code.
	  *
	  * @param code the localSymbol to subscribe for (e.g., "CLZ4")
	  * @return a configured Contract with FUT secType, NYMEX exchange, USD currency
	  */
	private def mkContract(code: String): Contract = {
		val con = new Contract
		con.secType("FUT")
		con.exchange(EXCHANGE)
		con.currency(CURRENCY)
		con.localSymbol(code)
		con
	}

	/** 
	  * Start only the Tick-by-Tick (or delayed top-of-book) leg for (reqId, code).
	  *
	  * For "realtime" streamType, subscribes tick-by-tick "AllLast".
	  * Otherwise requests delayed market data (top-of-book).
	  *
	  * @param cc active EClientSocket
	  * @param reqId IB request id
	  * @param code localSymbol to subscribe
	  */
	private def startFeedTbt(cc: EClientSocket, reqId: Int, code: String): Unit = {
		val con = mkContract(code)
		if (streamType == "realtime") {
			cc.reqTickByTickData(reqId, con, "AllLast", 0, false)
		} else {
			cc.reqMktData(reqId, con, "", false, false, null)
		}
	}

	/** 
	  * Start only the L2 (market depth) leg for (reqId, code). No-op for non-"realtime".
	  *
	  * @param cc active EClientSocket
	  * @param reqId IB request id
	  * @param code localSymbol to subscribe
	  */
	private def startFeedL2(cc: EClientSocket, reqId: Int, code: String): Unit = {
		if (streamType == "realtime") {
			val con = mkContract(code)
			cc.reqMktDepth(reqId, con, MAX_BOOK_DEPTH, false, null)
		}
	}

	/** 
	  * Cancel only the Tick-by-Tick leg (safe even if not active).
	  *
	  * @param cc active EClientSocket
	  * @param reqId IB request id to cancel
	  */
	private def cancelFeedTbt(cc: EClientSocket, reqId: Int): Unit = {
		cc.cancelTickByTickData(reqId)
	}

	/** 
	  * Cancel only the L2 leg (safe even if not active).
	  *
	  * @param cc active EClientSocket
	  * @param reqId IB request id to cancel
	  */
	private def cancelFeedL2(cc: EClientSocket, reqId: Int): Unit = {
		cc.cancelMktDepth(reqId, false)
	}

	/**
	  * Compute this pod's shard of the symbol universe.
	  *
	  * Behavior:
	  *   - If `shardingAlgorithm` is null: return the full symbol universe (no sharding).
	  *   - Else:
	  *       1) Discover peer pod names in the target namespace (default "ib-connector").
	  *       2) Determine this pod's name via `-Dpod.name`, then `POD_NAME`, else "this".
	  *       3) Call `shardingAlgorithm(peers, currentSharding)` to get the next assignment map.
	  *       4) Persist `currentSharding` and return this pod's assigned (reqId, code) list.
	  *
	  * Failure handling:
	  *   - Any failure to discover peers or an empty assigned bucket results in an exception.
	  *
	  * @return a list of (reqId, code) pairs assigned to this pod
	  * @throws IllegalStateException if K8s discovery fails or this pod has no assigned shard
	  */
	private def computeSymbolsShardThisPod(): List[(Int, String)] = {
		// No sharding algo => whole universe
		if (shardingAlgorithm == null) {
			return Connections.entriesSortedByReqId.toList
		}

		// Resolve self pod name (tests can set -Dpod.name=this)
		val selfName =
			Option(System.getProperty("pod.name"))
				.orElse(sys.env.get("POD_NAME"))
				.getOrElse("this")

		// Namespace to query
		val ns = sys.props.getOrElse("kubernetes.namespace", "ib-connector")

		// Peer discovery via the class-level Fabric8 client (no swallowing)
		val lst = kubernetesClient.pods().inNamespace(ns).list()
		require(lst != null && lst.getItems != null, s"k8s pods list failed for namespace=$ns")
		val peers = lst.getItems.asScala.flatMap { p =>
			val m = p.getMetadata
			if (m != null && m.getName != null) Some(m.getName) else None
		}.toList

		// Compute next sharding and pick our bucket
		val prev = if (currentSharding == null) Map.empty[String, List[(Int, String)]] else currentSharding
		val next = shardingAlgorithm(peers, prev)

		currentSharding = next
		val thisPodShard = next.getOrElse(selfName, Nil)

		if (thisPodShard==Nil) {
			throw new IllegalStateException()
		}
		thisPodShard
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
	  * Concurrency notes:
	  *   - This method enqueues a long-running task into the internal queue; it does not block the caller thread.
	  *   - All socket interactions within the loop are performed via `io.submit` to ensure single-threaded access.
	  *
	  * Error handling:
	  *   - If discovery is not ready after a grace period, an IllegalStateException is thrown and the task exits.
	  *   - Inner loop catches and suppresses exceptions to keep supervision running.
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

	/** 
	  * Stop all streams and flip Status to DROPPED (State left as-is).
	  *
	  * Enqueues cancel operations for both Tick-by-Tick and L2 legs across all known (reqId, code) pairs,
	  * and sets their Status to DROPPED. This does not transition `VALID` → `INVALID`; the wrapper will
	  * self-invalidate when appropriate per the state machine.
	  */
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
