// File: src/test/scala/TestDelayedStreaming.scala
package src.main.scala
import org.scalatest.funsuite.AnyFunSuite
import com.ib.client._
import java.util.concurrent.CountDownLatch
import scala.collection.mutable

class TestDelayedStreaming extends AnyFunSuite {

	test("starting simulated stream via connection manager") {
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
		val r = new EReader(client, sig); r.start()
		new Thread(() => {
			while (client.isConnected) { sig.waitForSignal(); r.processMsgs() }
			done.countDown()
		}, "ib-reader").start()

		client.startAPI()
		client.reqIds(-1)

		done.await()
	}
}
