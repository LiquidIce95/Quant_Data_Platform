// File: src/test/scala/TestDelayedStreaming.scala
package src.main.scala

import org.scalatest.funsuite.AnyFunSuite
import com.ib.client._
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.mutable
import java.io.{ByteArrayOutputStream, PrintStream}

/**
  * Integration test that:
  *  1) Starts EReader with Ewrapper (discovery-only).
  *  2) Requests CL/NG contract details (delayed mode) from the test (not from ConnManager).
  *  3) Starts ConnManager on its own thread and calls startStreams() to request L1 streams.
  *  4) Captures console and asserts that delayed L1 ticks arrived.
  *
  * IMPORTANT: IB Gateway or TWS must be running for this test.
  */
class TestDelayedStreaming extends AnyFunSuite {

	test("starting simulated stream via connection manager") {
		val capture = new ByteArrayOutputStream()

		Console.withOut(new PrintStream(capture)) {
			val stateMap  = mutable.Map.empty[String,(ConnState,ConnState)]
			val lookupMap = mutable.Map.empty[Int, String]
			val producer  = null
			val done      = new CountDownLatch(1)

			// IB plumbing
			val sig = new EJavaSignal()
			val ew = new EwrapperImplementation(producer, lookupMap, stateMap,"ticklast", "l2-data")
			val client = new EClientSocket(ew, sig)
			val io = new ClientIo(client)
			val connMan = new ConnManager(client, stateMap, lookupMap, "delayed", io)
			connMan.start()

			// connect & reader loop
			client.eConnect("127.0.0.1", 4002, 1)
			val r = new EReader(client, sig); r.start()
			new Thread(() => {
				while (client.isConnected) { sig.waitForSignal(); r.processMsgs() }
				done.countDown()
			}, "ib-reader").start()

			// kick server→client flow
			client.startAPI()
			client.reqIds(-1)

			// discovery: request contract details (wrapper will collect and populate maps)
			// use delayed mode for your setup
			val EXCHANGE = "NYMEX"; val CURRENCY = "USD"
			def futQuery(sym: String): Contract = {
				val k = new Contract
				k.symbol(sym); k.secType("FUT"); k.exchange(EXCHANGE); k.currency(CURRENCY)
				k
			}
			client.reqContractDetails(1001, futQuery("CL"))
			client.reqContractDetails(1002, futQuery("NG"))
			Thread.sleep(6_000L)
			// let discovery complete and then start streams from the manager
			// (ConnManager will re-check and either proceed or throw if still empty after retry)
			connMan.startStreams()

			// allow some time to see ticks on the console; then end test
			new Thread(() => {
				try Thread.sleep(10_000L) catch { case _: Throwable => () }
				try client.eDisconnect() catch { case _: Throwable => () }
			}, "shutdown").start()
			done.await()

		}

		val out = capture.toString("UTF-8")
		val priceRe =
			raw"""\[tickPrice]\[(\d+)]\[[^\]]+]\s+(delayedBid|delayedAsk)\s=\s(\d+\.\d+)""".r
		val sizeRe  =
			raw"""\[tickSize]\[(\d+)]\[[^\]]+]\s+(delayedBidSize|delayedAskSize)\s=\s(\d+)""".r

		val priceCount = priceRe.findAllMatchIn(out).size
		val sizeCount  = sizeRe.findAllMatchIn(out).size

		val expectedMinPerType = 30
		assert(
			priceCount >= expectedMinPerType,
			s"Expected ≥ $expectedMinPerType valid [tickPrice] lines, found $priceCount.\n" +
			s"First lines:\n${out.linesIterator.take(40).mkString("\n")}"
		)
		assert(
			sizeCount >= expectedMinPerType,
			s"Expected ≥ $expectedMinPerType valid [tickSize] lines, found $sizeCount.\n" +
			s"First lines:\n${out.linesIterator.take(40).mkString("\n")}"
		)

	}
}
