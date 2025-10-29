// File: src/main/scala/SimulateStreaming.scala
package src.main.scala

import com.ib.client.{Decimal, TickAttribLast}
import scala.collection.mutable
import scala.util.Random
import java.util.concurrent.{Executors, TimeUnit}
import scala.math

/**
  * Synthetic data generator for local testing without a live IB connection.
  *
  * Provides two scenarios:
  *   1) Tick-only simulation producing Tick-by-Tick "Last" events.
  *   2) Tick + L2 simulation that emits a legal, repeating sequence of L2 ops.
  *
  * Both scenarios:
  *   - Initialize the minimal [[Connections]] state and authorize a dummy [[ConnManager]]
  *     so that [[EwrapperImplementation]] will emit.
  *   - Publish JSON events via [[KafkaProducerApi]] to the configured topics.
  *
  * Assumptions:
  *   - Kafka is reachable at the configured bootstrap servers.
  *   - Only one synthetic symbol ("CL_SIM_FUT") is produced per scenario.
  */
object SimulateStreaming {

	/** 
	  * Tick-only simulation (unchanged logic; pass dummy ConnManager).
	  *
	  * Emits Tick-by-Tick "Last" events at a fixed interval with Gaussian price noise.
	  * Sets up a minimal environment so the wrapper processes and publishes the events.
	  *
	  * @param timeIntervalMs interval in milliseconds between synthetic ticks
	  * @param maxTicks       maximum number of ticks to emit before shutting down
	  */
	def scenarioTickByTickLast(timeIntervalMs: Long = 300L, maxTicks: Double = math.pow(10, 6)): Unit = {
		val reqId = 5001
		val reqIdToCode = mutable.Map[Int, String](reqId -> "CL_SIM_FUT")
		val code = "CL_SIM_FUT"

		val producer = KafkaProducerApi()
		val dummyStateMap = mutable.Map.empty[String,(ConnState,ConnState)]
		val ew = new EwrapperImplementation(producer,"ticklast", "l2-data")

		// --- minimal init so wrapper emits ---
		Connections.reset()
		val mgr = new ConnManager(null, null, "realtime", null) // authorize as manager
		Connections.setActors(ew, mgr)
		Connections.putLookup(reqId, code)
		Connections.ensureEntry(code)
		Connections.setStatus(mgr, code, isL2 = false, Connections.ON)
		Connections.setStatus(mgr, code, isL2 = true,  Connections.ON)
		Connections.setState(mgr,  code, isL2 = false, ConnState.VALID)
		Connections.setState(mgr,  code, isL2 = true,  ConnState.VALID)
		// -------------------------------------

		val exec = Executors.newSingleThreadScheduledExecutor()
		val rand = new Random()
		val basePrice = 80.0

		val task = new Runnable {
			private var count = 0
			override def run(): Unit = {
				try {
					val tickType = if (rand.nextBoolean()) 0 else 1
					val epochSeconds = System.currentTimeMillis() / 1000
					val price = basePrice + rand.nextGaussian() * 0.1
					val size: Decimal = null
					val attr: TickAttribLast = null
					val exchange = "NYMEX"
					val special = ""

					ew.tickByTickAllLast(reqId, tickType, epochSeconds, price, size, attr, exchange, special)

					count += 1
					if (count >= maxTicks) {
						producer.flush()
						producer.close()
						exec.shutdown()
					}
				} catch { case _: Throwable => () }
			}
		}

		exec.scheduleAtFixedRate(task, 0L, math.max(1L, timeIntervalMs), TimeUnit.MILLISECONDS)
		exec.awaitTermination(Long.MaxValue, TimeUnit.DAYS)
	}

	/** 
	  * Tick + L2 simulation with a fixed, legal, repeating sequence of L2 ops.
	  *
	  * Sets up a per-symbol [[BookState]] and applies a loop of INSERT/UPDATE/DELETE
	  * operations that respect monotonicity and no-hole constraints. Also emits
	  * Tick-by-Tick "Last" events at the same cadence.
	  *
	  * Implementation notes:
	  *   - Pre-creates a BookState for the simulated symbol via reflection to ensure
	  *     the first L2 op can be applied immediately.
	  *   - Uses null `Decimal` to represent zero sizes in DELETE-like steps.
	  *
	  * @param timeIntervalMs interval in milliseconds between synthetic events
	  * @param maxTicks       maximum number of event cycles before shutdown
	  */
	def scenarioTickByTickLastAndL2AllNoProblems(timeIntervalMs: Long = 300L, maxTicks: Double = math.pow(10, 6)): Unit = {
		println("starting the tickbyticLast and L2 data no problems simulation")
		val reqId = 6001
		val code = "CL_SIM_FUT"
		val reqIdToCode = mutable.Map[Int, String](reqId -> code)

		val producer = KafkaProducerApi()
		val dummyStateMap = mutable.Map.empty[String,(ConnState,ConnState)]
		val ew = new EwrapperImplementation(producer,"ticklast", "l2-data")

		// --- minimal init so wrapper emits ---
		Connections.reset()
		val mgr = new ConnManager(null, null, "realtime", null) // authorize as manager
		Connections.setActors(ew, mgr)
		Connections.putLookup(reqId, code)
		Connections.ensureEntry(code)
		Connections.setStatus(mgr, code, isL2 = false, Connections.ON)
		Connections.setStatus(mgr, code, isL2 = true,  Connections.ON)
		Connections.setState(mgr,  code, isL2 = false, ConnState.VALID)
		Connections.setState(mgr,  code, isL2 = true,  ConnState.VALID)
		// -------------------------------------

		// Pre-create BookState for CL_SIM_FUT by poking the private HashMap via reflection.
		val bsField = classOf[EwrapperImplementation].getDeclaredField("BookStatesMap")
		bsField.setAccessible(true)
		val depthField = classOf[EwrapperImplementation].getDeclaredField("MAX_BOOK_DEPTH")
		depthField.setAccessible(true)

		val books = bsField.get(ew).asInstanceOf[mutable.HashMap[String, BookState]]
		val maxDepth = depthField.getInt(ew)
		books.update(code, new BookState(maxDepth))

		val exec = Executors.newSingleThreadScheduledExecutor()
		val rand = new Random()
		val basePrice = 80.0

		/** 
		  * Simple L2 operation descriptor used by the simulation loop.
		  * @param op      0=INSERT, 1=UPDATE, 2=DELETE
		  * @param side    0=ask, 1=bid
		  * @param pos     level index
		  * @param price   price value (for insert/update)
		  * @param sizeStr size in string form; "0" is treated as null Decimal
		  */
		case class L2Op(op: Int, side: Int, pos: Int, price: Double, sizeStr: String)
		val seq: Array[L2Op] = Array(
			L2Op(0, 0, 0, 80.00, "1"),
			L2Op(0, 1, 0, 79.95, "1"),
			L2Op(1, 0, 0, 80.01, "1.2"),
			L2Op(0, 0, 1, 80.05, "1.0"),
			L2Op(2, 0, 1, 0.0,  "0"),
			L2Op(2, 0, 0, 0.0,  "0"),
			L2Op(2, 1, 0, 0.0,  "0"),
			L2Op(0, 1, 0, 79.90, "1"),
			L2Op(1, 1, 0, 79.89, "1.1"),
			L2Op(0, 1, 1, 79.80, "1.0"),
			L2Op(2, 1, 1, 0.0,  "0"),
			L2Op(2, 1, 0, 0.0,  "0")
		)

		val marketMaker = "simulated_market_maker"
		val isSmartDepth = false

		val task = new Runnable {
			private var count = 0
			override def run(): Unit = {
				try {
					// Emit tick
					val tickType = if (rand.nextBoolean()) 0 else 1
					val epochSeconds = System.currentTimeMillis() / 1000
					val lastPrice = basePrice + rand.nextGaussian() * 0.1
					val lastSize: Decimal = null
					val attr: TickAttribLast = null
					val exchange = "NYMEX"
					val special = ""
					ew.tickByTickAllLast(reqId, tickType, epochSeconds, lastPrice, lastSize, attr, exchange, special)

					// Apply next L2 op
					val op = seq(count % seq.length)
					val decSize: Decimal =
						if (op.sizeStr == "0") null
						else Decimal.get(java.lang.Double.parseDouble(op.sizeStr))
					ew.updateMktDepthL2(
						reqId,
						op.pos,
						marketMaker,
						op.op,
						op.side,
						op.price,
						decSize,
						isSmartDepth
					)

					count += 1
					if (count >= maxTicks) {
						producer.flush()
						producer.close()
						exec.shutdown()
					}
				} catch {
					case e: Throwable =>
						System.err.println(s"[SimulateStreaming] ${e.getClass.getName}: ${e.getMessage}")
						e.printStackTrace(System.err)
				}
			}
		}

		exec.scheduleAtFixedRate(task, 0L, math.max(1L, timeIntervalMs), TimeUnit.MILLISECONDS)
		exec.awaitTermination(Long.MaxValue, TimeUnit.DAYS)
	}

	/**
	  * Entrypoint for running a chosen simulation scenario from the command line.
	  *
	  * Usage: `SimulateStreaming <simId> <intervalMs> <maxTicks>`
	  *   - simId: 1 = tick-only; 2 = tick+L2 (default 2)
	  *   - intervalMs: emission interval in milliseconds (default 300)
	  *   - maxTicks: maximum number of cycles before shutdown (default 1e6)
	  *
	  * @param args command-line arguments
	  */
	def main(args: Array[String]): Unit = {
		require(args.length == 3, "Usage: SimulateStreaming <simId> <intervalMs> <maxTicks>")
		val simId = scala.util.Try(args(0).toInt).getOrElse(2)
		val intervalMs = scala.util.Try(args(1).toLong).getOrElse(300L)
		val maxTicks = scala.util.Try(args(2).toDouble).getOrElse(math.pow(10, 6))

		simId match {
			case 1 => scenarioTickByTickLast(intervalMs, maxTicks)
			case 2 => scenarioTickByTickLastAndL2AllNoProblems(intervalMs, maxTicks)
			case _ => scenarioTickByTickLastAndL2AllNoProblems(intervalMs, maxTicks)
		}
	}
}
