package src.main.scala
import com.ib.client._
import java.util.concurrent.{CountDownLatch, TimeUnit}

object IbAccountSummary {
  def main(args: Array[String]): Unit = {
    println("executing IbAccountSummary")
    val host     = sys.env.getOrElse("IB_HOST", "host.docker.internal")
    val port     = sys.env.getOrElse("IB_PORT", "4002").toInt   // 4002=Gateway paper, 4001=Gateway live, 7497=TWS paper
    val clientId = sys.env.getOrElse("IB_CLIENT_ID", "0").toInt

    val done    = new CountDownLatch(1)
    val signal  = new EJavaSignal()
    @volatile var client: EClientSocket = null

    val wrapper = new DefaultEWrapper {
      override def nextValidId(orderId: Int): Unit = {
        // Ask for two simple fields (comma-separated tags OK)
        client.reqAccountSummary(9001, "All", "AccountType,TotalCashValue")
        println("nextValidId called")
      }

      override def accountSummary(reqId: Int, account: String, tag: String, value: String, currency: String): Unit =
        println(s"$tag for $account = $value $currency")

      override def accountSummaryEnd(reqId: Int): Unit = {
        client.cancelAccountSummary(reqId)
        client.eDisconnect()
        done.countDown()
      }

      // Newer API has 5-arg error
      override def error(reqId: Int, time: Long, code: Int, msg: String, advancedOrderRejectJson: String): Unit =
        System.err.println(s"[err] code=$code msg=$msg")
    }

    client = new EClientSocket(wrapper, signal)

    client.eConnect(host, port, clientId)
    val reader = new EReader(client, signal)
    println("starting the reader now")
    reader.start()
    new Thread(() => {
      while (client.isConnected) { 
          signal.waitForSignal(); 
          reader.processMsgs();
          println(f"status of connection is ${client.isConnected()}")
      }
    }, "ib-reader").start()

    if (!done.await(10, TimeUnit.SECONDS)) {
      System.err.println("timeout waiting for account summary")
      client.eDisconnect()
    }
  }
}

