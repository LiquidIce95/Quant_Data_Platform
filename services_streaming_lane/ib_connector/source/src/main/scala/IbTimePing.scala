import com.ib.client._
import java.util.concurrent.{CountDownLatch, TimeUnit}

object IbTimePing extends App {
  // Tweak these via env if needed: IB_HOST, IB_PORT, IB_CLIENT_ID
  val host     = sys.env.getOrElse("IB_HOST", "host.docker.internal")
  val port     = sys.env.getOrElse("IB_PORT", "4002").toInt   // 4002=Gateway paper, 4001=Gateway live, 7497=TWS paper
  val clientId = sys.env.getOrElse("IB_CLIENT_ID", "0").toInt

  val done   = new CountDownLatch(1)

  // Minimal wrapper with only the callbacks we care about
  class Wrapper extends DefaultEWrapper {
    override def nextValidId(orderId: Int): Unit =
      client.reqCurrentTime()                         // ask for server time as soon as connected

    override def currentTime(time: Long): Unit = {
      println(s"serverTime=$time")
      client.eDisconnect()
      done.countDown()
    }

    // 5-arg signature in newer APIs
    override def error(reqId: Int, time: Long, code: Int, msg: String, advancedOrderRejectJson: String): Unit =
      System.err.println(s"[err] code=$code msg=$msg")
  }

  val wrapper = new Wrapper
  val signal  = new EJavaSignal()
  val client  = new EClientSocket(wrapper, signal)

  // expose client to wrapper methods
  wrapper.getClass.getDeclaredFields.find(_.getName == "client").foreach(_.setAccessible(true))
  // NOTE: not strictly necessary—just use the outer `client` inside Wrapper via enclosing scope if you prefer.

  // connect and start reader loop
  client.eConnect(host, port, clientId)
  val reader = new EReader(client, signal)
  reader.start()
  new Thread(() => {
    while (client.isConnected) {
      signal.waitForSignal()
      reader.processMsgs()
    }
  }, "ib-reader").start()

  // wait up to 10s for reply
  if (!done.await(10, TimeUnit.SECONDS)) {
    System.err.println("timeout waiting for serverTime")
    client.eDisconnect()
  }
}

