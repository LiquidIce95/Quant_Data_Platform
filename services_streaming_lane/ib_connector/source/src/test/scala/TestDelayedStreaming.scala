// File: src/test/scala/TestDelayedStreaming.scala
package src.main.scala

import org.scalatest.funsuite.AnyFunSuite
import com.ib.client._
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.mutable
import java.io.{ByteArrayOutputStream, PrintStream}

class TestDelayedStreaming extends AnyFunSuite {
  //IMPORTANT: TWS  OR IB GATEWAY NEED TO BE RUNNING FOR THIS TEST TO WORK!!!
  test("starting simulated stream via connection manager") {
    val capture = new ByteArrayOutputStream()

    Console.withOut(new PrintStream(capture)) {
      val stateMap  = mutable.Map.empty[String,(ConnState,ConnState)]
      val lookupMap = mutable.Map.empty[Int, String]
      val producer  = null
      val done      = new CountDownLatch(1)

      val sig = new EJavaSignal()

      val ew = new EwrapperImplementation(producer, lookupMap, "ticklast", "l2-data")
      val client = new EClientSocket(ew, sig)
      val io = new ClientIo(client)
      val connMan = new ConnManager(client, stateMap, lookupMap, "delayed", io)
      ew.setConnManager(connMan)
      connMan.start()

      client.eConnect("127.0.0.1", 4002, 1)
      val reader = new EReader(client, sig); reader.start()
      new Thread(() => {
        while (client.isConnected) { sig.waitForSignal(); reader.processMsgs() }
        done.countDown()
      }, "ib-reader").start()

      client.startAPI()
      client.reqIds(-1)

      // --- Collection window: let messages flow for ~8 seconds ---
      Thread.sleep(8000)

      // Disconnect so the reader thread exits and the latch is released
      client.eDisconnect()
      // Safety timeout so the test never hangs
      done.await(10, TimeUnit.SECONDS)
    }

    val out = capture.toString("UTF-8")

    // [tickPrice][<int>][<code>] (delayedBid|delayedAsk) = <decimal>
    val priceRe =
      raw"""\[tickPrice]\[(\d+)]\[[^\]]+]\s+(delayedBid|delayedAsk)\s=\s(\d+\.\d+)""".r
    // [tickSize][<int>][<code>] (delayedBidSize|delayedAskSize) = <int>
    val sizeRe  =
      raw"""\[tickSize]\[(\d+)]\[[^\]]+]\s+(delayedBidSize|delayedAskSize)\s=\s(\d+)""".r

    val priceCount = priceRe.findAllMatchIn(out).size
    val sizeCount  = sizeRe.findAllMatchIn(out).size

    val expectedMinPerType = 50
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
