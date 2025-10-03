import com.ib.client._
import java.util.concurrent.CountDownLatch
import scala.collection.mutable.ListBuffer
import scala.util.Try

object StreamingL2 {
  // ===== constants (edit if needed) =====
  private val HOST        = "127.0.0.1"   // IB Gateway/TWS host
  private val PORT        = 4002          // 4002 paper GW, 7497 paper TWS
  private val CLIENT_ID   = 1
  private val SYMBOL      = "CL"
  private val EXCHANGE    = "NYMEX"       // CME/NYMEX Globex
  private val CURRENCY    = "USD"
  private val DEPTH_ROWS  = 10
  private val SMART_DEPTH = false

  def main(args: Array[String]): Unit = {
    val signal = new EJavaSignal()
    val done   = new CountDownLatch(1)
    @volatile var client: EClientSocket = null
    val cds    = ListBuffer.empty[ContractDetails]

    def futQuery(symbol: String): Contract = {
      val c = new Contract
      c.symbol(symbol); c.secType("FUT"); c.exchange(EXCHANGE); c.currency(CURRENCY)
      c // leave lastTradeDateOrContractMonth empty → get all months
    }

    def frontMonth(all: Seq[ContractDetails]): Option[Contract] = {
      def toKey(s: String) = Try(s.take(6).toInt).getOrElse(Int.MaxValue)
      all.sortBy(cd => toKey(cd.contract.lastTradeDateOrContractMonth)).headOption.map(_.contract)
    }

    val wrapper = new DefaultEWrapper {
      override def connectAck(): Unit = { client.startAPI(); client.reqIds(-1) }

      override def nextValidId(orderId: Int): Unit = {
        println(s"[ib] connected → querying contract details for $SYMBOL FUT $EXCHANGE")
        client.reqContractDetails(1001, futQuery(SYMBOL))
      }

      override def contractDetails(reqId: Int, cd: ContractDetails): Unit = cds += cd

      override def contractDetailsEnd(reqId: Int): Unit = {
        frontMonth(cds.toList) match {
          case Some(con) =>
            val m = con.localSymbol
            val mth = con.lastTradeDateOrContractMonth
            println(s"[ib] front month resolved → $m ($mth). Subscribing L2…")
            client.reqMktDepth(2001, con, DEPTH_ROWS, SMART_DEPTH, null)
          case None =>
            System.err.println("[ib] no contract months returned"); client.eDisconnect(); done.countDown()
        }
      }

      // L2 book updates (SMART or venue-specific)
      override def updateMktDepthL2(reqId: Int, position: Int, marketMaker: String,
                                    operation: Int, side: Int, price: Double,
                                    size: Decimal, isSmartDepth: Boolean): Unit = {
        val op  = operation match { case 0 => "ADD"; case 1 => "UPD"; case 2 => "DEL"; case x => s"OP$x" }
        val s   = if (side == 0) "BID" else "ASK"
        println(f"L2[$position%2d] $s%-3s $op%-3s @ $price%10.4f x $size%8s mm=$marketMaker smart=$isSmartDepth")
      }

      // (Non-venue L2 callback on older configs)
      override def updateMktDepth(reqId: Int, position: Int, operation: Int,
                                  side: Int, price: Double, size: Decimal): Unit =
        println(f"L2[$position%2d] ${if (side==0) "BID" else "ASK"} op=$operation @ $price%10.4f x $size%8s")

      // errors / logs
      override def error(reqId: Int, time: Long, code: Int, msg: String, adv: String): Unit =
        System.err.println(s"[err] code=$code msg=$msg")
      override def connectionClosed(): Unit = { println("[ib] connection closed"); done.countDown() }
    }

    client = new EClientSocket(wrapper, signal)
    client.eConnect(HOST, PORT, CLIENT_ID)

    val reader = new EReader(client, signal); reader.start()
    new Thread(() => while (client.isConnected) { signal.waitForSignal(); reader.processMsgs() }, "ib-reader").start()

    done.await() // keep running; Ctrl+C to stop
  }
}
