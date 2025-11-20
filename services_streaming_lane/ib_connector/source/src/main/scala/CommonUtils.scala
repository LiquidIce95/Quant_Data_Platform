package src.main.scala 
import scala.concurrent._
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.util.{Failure, Success}


object CommonUtils {
    implicit val ec: ExecutionContext = ExecutionContext.global
	val scheduler = Executors.newSingleThreadScheduledExecutor()

	def scheduleAtFixedRate(intervalMs: Long)(f: => Unit): Unit = {
		def loop(): Unit = {
			Future(f).onComplete {
				case Failure(e) =>
					e.printStackTrace()		// or println(s"scheduled task failed: ${e.getMessage}")
					scheduler.schedule(
						new Runnable { def run(): Unit = loop() },
						intervalMs,
						TimeUnit.MILLISECONDS
					)
				case Success(_) =>
					scheduler.schedule(
						new Runnable { def run(): Unit = loop() },
						intervalMs,
						TimeUnit.MILLISECONDS
					)
			}
		}
		loop()
	}

	def buildMonthYearCode(expiry: String): String = {
		assert(expiry.length()==8)

		val yearSuffix = expiry.substring(2, 4)
		val monthStr = expiry.substring(4, 6)
		val monthOpt = monthStr.toIntOption
		monthOpt match {
			case Some(m) =>
				val monthCode =
					m match {
						case 1  => "F"
						case 2  => "G"
						case 3  => "H"
						case 4  => "J"
						case 5  => "K"
						case 6  => "M"
						case 7  => "N"
						case 8  => "Q"
						case 9  => "U"
						case 10 => "V"
						case 11 => "X"
						case 12 => "Z"
						case _  => ""
					}
				monthCode + yearSuffix
			case None =>
				expiry
		}
	
    }
}