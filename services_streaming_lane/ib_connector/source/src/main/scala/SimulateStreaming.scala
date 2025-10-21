// File: src/main/scala/SimulateStreaming.scala
package src.main.scala

import com.ib.client.{Decimal, TickAttribLast}
import scala.collection.mutable
import scala.util.Random
import java.util.concurrent.{Executors, TimeUnit}
import scala.math

object SimulateStreaming {

	/** Tick-only simulation (unchanged) */
	def scenarioTickByTickLast(timeIntervalMs: Long = 300L, maxTicks: Double = math.pow(10, 6)): Unit = {
		val reqId = 5001
		val reqIdToCode = mutable.Map[Int, String](reqId -> "CL_SIM_FUT")

		val producer = KafkaProducerApi()
		val ew       = new EwrapperImplementation(producer, reqIdToCode)

		val exec = Executors.newSingleThreadScheduledExecutor()
		val rand = new Random()
		val basePrice = 80.0

		val task = new Runnable {
			private var count = 0
			override def run(): Unit = {
				try {
					val tickType     = if (rand.nextBoolean()) 0 else 1 // 0="Last", 1="AllLast"
					val epochSeconds = System.currentTimeMillis() / 1000
					val price        = basePrice + rand.nextGaussian() * 0.1
					val size: Decimal = null
					val attr: TickAttribLast = null
					val exchange = "NYMEX"
					val special  = ""

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

	/** Tick + L2 simulation with a fixed, legal, repeating sequence of L2 ops. */
	def scenarioTickByTickLastAndL2AllNoProblems(timeIntervalMs: Long = 300L, maxTicks: Double = math.pow(10, 6)): Unit = {
    println("starting the tickbyticLast and L2 data no problems simulation")
		val reqId = 6001
		val code  = "CL_SIM_FUT"
		val reqIdToCode = mutable.Map[Int, String](reqId -> code)

		val producer = KafkaProducerApi()
		val ew       = new EwrapperImplementation(producer, reqIdToCode)
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

		// Fixed legal sequence (repeats). IB meanings: op 0=insert, 1=update, 2=delete. side 0=ask, 1=bid.
		// Starts from empty book and maintains monotonicity and no-holes across inserts/deletes.
		case class L2Op(op: Int, side: Int, pos: Int, price: Double, sizeStr: String)
		val seq: Array[L2Op] = Array(
			// Build asks side
      // --- SEED (minimal book: ask L0, bid L0) ---
      L2Op(0, 0, 0, 80.00, "1"),    // ask L0
      L2Op(0, 1, 0, 79.95, "1"),    // bid L0

      // --- ONE UPDATE ---
      L2Op(1, 0, 0, 80.01, "1.2"),  // update ask L0 (still <= any future ask L1)

      // --- ONE INSERT ---
      L2Op(0, 0, 1, 80.05, "1.0"),  // insert ask L1 (>= ask L0) -> monotonic ok

      // --- ONE DELETE ---
      L2Op(2, 0, 1, 0.0,  "0"),     // delete ask L1 -> no holes

      // --- CLEANUP (delete everything) ---
      L2Op(2, 0, 0, 0.0,  "0"),     // delete ask L0
      L2Op(2, 1, 0, 0.0,  "0"),     // delete bid L0

      // --- RESEED OTHER SIDE (keeps the cycle alive) ---
      L2Op(0, 1, 0, 79.90, "1"),    // bid L0
      L2Op(1, 1, 0, 79.89, "1.1"),  // update bid L0
      L2Op(0, 1, 1, 79.80, "1.0"),  // insert bid L1 (<= bid L0)
      L2Op(2, 1, 1, 0.0,  "0"),     // delete bid L1
      L2Op(2, 1, 0, 0.0,  "0")      // cleanup bid L0
		)

		val marketMaker = "simulated_market_maker"
		val isSmartDepth = false

		val task = new Runnable {
			private var count = 0
			override def run(): Unit = {
				try {
					// ----- TBT last -----
					val tickType     = if (rand.nextBoolean()) 0 else 1 // 0="Last", 1="AllLast"
					val epochSeconds = System.currentTimeMillis() / 1000
					val lastPrice    = basePrice + rand.nextGaussian() * 0.1
					val lastSize: Decimal = null
					val attr: TickAttribLast = null
					val exchange = "NYMEX"
					val special  = ""
					ew.tickByTickAllLast(reqId, tickType, epochSeconds, lastPrice, lastSize, attr, exchange, special)

					// ----- L2 scripted op -----
					val op = seq(count % seq.length)
          val decSize: Decimal = if (op.sizeStr == "0") null else Decimal.get(java.lang.Double.parseDouble(op.sizeStr))
					ew.updateMktDepthL2(
						reqId         = reqId,
						position      = op.pos,
						marketMaker   = marketMaker,
						operation     = op.op,
						side          = op.side,
						price         = op.price,
						size          = decSize,
						isSmartDepth  = isSmartDepth
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

	def main(args: Array[String]): Unit = {
		require(args.length == 3, "Usage: SimulateStreaming <simId> <intervalMs> <maxTicks>")
		val simId      = scala.util.Try(args(0).toInt).getOrElse(2)
		val intervalMs = scala.util.Try(args(1).toLong).getOrElse(300L)
		val maxTicks   = scala.util.Try(args(2).toDouble).getOrElse(math.pow(10, 6))

		simId match {
			case 1 => scenarioTickByTickLast(intervalMs, maxTicks)
			case 2 => scenarioTickByTickLastAndL2AllNoProblems(intervalMs, maxTicks)
			case _ => scenarioTickByTickLastAndL2AllNoProblems(intervalMs, maxTicks)
		}
	}
}
