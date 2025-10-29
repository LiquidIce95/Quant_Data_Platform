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
			// 1) prepare Connections with no actors yet (no one can mutate)
			Connections.reset()

			// 2) create wrapper/manager instances using Connections
			val producer  = null
			val ew        = new EwrapperImplementation(producer)
			val sig       = new EJavaSignal()
			val client    = new EClientSocket(ew, sig)
			val io        = new ClientIo(client)
			val connMan   = new ConnManager(client, io, "delayed")

			// 3) register actors (now guarded mutations are possible)
			Connections.setActors(ew, connMan)

			// 4) IB reader plumbing
			val done = new CountDownLatch(1)
			connMan.start()

			client.eConnect("127.0.0.1", 4002, 1)
			val r = new EReader(client, sig); r.start()
			new Thread(() => {
				while (client.isConnected) { sig.waitForSignal(); r.processMsgs() }
				done.countDown()
			}, "ib-reader").start()

			client.startAPI()
			client.reqIds(-1)

			// 5) discovery: request CL/NG contract details (wrapper will fill Connections)
			def futQuery(sym: String): Contract = {
				val k = new Contract
				k.symbol(sym); k.secType("FUT"); k.exchange("NYMEX"); k.currency("USD")
				k
			}
			client.reqContractDetails(1001, futQuery("CL"))
			client.reqContractDetails(1002, futQuery("NG"))

			// 6) let discovery populate, then start streams
			Thread.sleep(6_000L)
			connMan.startStreams(500L)

			// 7) run for a bit and shutdown
			new Thread(() => {
				try Thread.sleep(25_000L) catch { case _: Throwable => () }
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
