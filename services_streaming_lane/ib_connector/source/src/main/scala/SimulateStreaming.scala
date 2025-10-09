package src.main.scala

import com.ib.client.{Decimal, TickAttribLast}
import scala.collection.mutable
import scala.util.{Random, Try}
import java.util.concurrent.{Executors, TimeUnit}
import scala.math

object SimulateStreaming {

  /**
    * Simulate a tick-by-tick "Last"/"AllLast" stream by repeatedly invoking
    * EwrapperImplementation.tickByTickAllLast at a fixed interval.
    *
    * @param timeIntervalMs  cadence in milliseconds between ticks
    * @param maxTicks the maximum amount of tick to 'stream'
    *
    * Behavior:
    *  - Produces up to SIM_MAX_TICKS ticks (default 50) then shuts down.
    *  - Uses a real Kafka producer (KafkaProducerApi()); make sure Kafka is reachable
    *    or swap in a print-only producer for local dry-runs.
    *  - Maps a single reqId -> trading symbol for the simulated stream.
    */
  def scenarioTickByTickLast(timeIntervalMs: Long = 300L, maxTicks : Double = math.pow(10,6)): Unit = {
    val reqId = 5001
    val reqIdToCode = mutable.Map[Int, String](reqId -> "CL_SIM_FUT")

    val producer = KafkaProducerApi()                          // real producer; ensure Kafka up for end-to-end tests
    val ew       = new EwrapperImplementation(producer, reqIdToCode)

    val exec = Executors.newSingleThreadScheduledExecutor()
    val rand = new Random()

    val basePrice = 80.0
    

    val task = new Runnable {
      private var count = 0

      override def run(): Unit = {
        try {
          val tickType       = if (rand.nextBoolean()) 0 else 1 // 0="Last", 1="AllLast"
          val epochSeconds   = System.currentTimeMillis() / 1000
          val price          = basePrice + rand.nextGaussian() * 0.1
          val size: Decimal  = null                 // keep null; your transform handles null -> "0"
          val attr: TickAttribLast = null          // or new TickAttribLast() if you prefer
          val exchange       = "NYMEX"
          val special        = ""

          ew.tickByTickAllLast(
            reqId,
            tickType,
            epochSeconds,
            price,
            size,
            attr,
            exchange,
            special
          )

          count += 1
          if (count >= maxTicks) {
            producer.flush()
            producer.close()
            exec.shutdown()
          }
        } catch {
          case _: Throwable => () // swallow for simulation; your tests can hook logs if needed
        }
      }
    }

    exec.scheduleAtFixedRate(
      task,
      0L,
      math.max(1L, timeIntervalMs),
      TimeUnit.MILLISECONDS
    )

    // Block until the scheduled task finishes (good for integration tests)
    exec.awaitTermination(Long.MaxValue, TimeUnit.DAYS)
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 3,
      "Usage: SimulateStreaming <simId> <intervalMs> <maxTicks>")

    val simId      = scala.util.Try(args(0).toInt).getOrElse(1)
    val intervalMs = scala.util.Try(args(1).toLong).getOrElse(300L)
    val maxTicks   = scala.util.Try(args(2).toDouble).getOrElse(math.pow(10, 6))

    simId match {
      case 1 => scenarioTickByTickLast(intervalMs, maxTicks)
      case _ => scenarioTickByTickLast(intervalMs, maxTicks) 
    }
  }

}
