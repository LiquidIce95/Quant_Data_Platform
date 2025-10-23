// File: src/main/scala/SimulateStreaming.scala
package src.main.scala

import com.ib.client.{Decimal, TickAttribLast}
import scala.collection.mutable
import scala.util.Random
import java.util.concurrent.{Executors, TimeUnit}
import scala.math

object SimulateStreaming {

	/** Tick-only simulation (unchanged logic; pass dummy ConnManager) */
	def scenarioTickByTickLast(timeIntervalMs: Long = 300L, maxTicks: Double = math.pow(10, 6)): Unit = {
		val reqId = 5001
		val reqIdToCode = mutable.Map[Int, String](reqId -> "CL_SIM_FUT")

		val producer = KafkaProducerApi()
		val dummyStateMap = mutable.Map.empty[String,(ConnState,ConnState)]
		val ew = new EwrapperImplementation(producer, reqIdToCode, dummyStateMap,"ticklast", "l2-data")

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

	/** Tick + L2 simulation with a fixed, legal, repeating sequence of L2 ops. */
	def scenarioTickByTickLastAndL2AllNoProblems(timeIntervalMs: Long = 300L, maxTicks: Double = math.pow(10, 6)): Unit = {
		println("starting the tickbyticLast and L2 data no problems simulation")
		val reqId = 6001
		val code = "CL_SIM_FUT"
		val reqIdToCode = mutable.Map[Int, String](reqId -> code)

		val producer = KafkaProducerApi()
		val dummyStateMap = mutable.Map.empty[String,(ConnState,ConnState)]
		val ew = new EwrapperImplementation(producer, reqIdToCode, dummyStateMap,"ticklast", "l2-data")

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
					val tickType = if (rand.nextBoolean()) 0 else 1
					val epochSeconds = System.currentTimeMillis() / 1000
					val lastPrice = basePrice + rand.nextGaussian() * 0.1
					val lastSize: Decimal = null
					val attr: TickAttribLast = null
					val exchange = "NYMEX"
					val special = ""
					ew.tickByTickAllLast(reqId, tickType, epochSeconds, lastPrice, lastSize, attr, exchange, special)

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
