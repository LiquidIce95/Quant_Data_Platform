import com.ib.client._
import java.util.concurrent.CountDownLatch
import scala.collection.mutable.ListBuffer
import scala.util.Try

object StreamingTickByTickTrades {
  // constants
  private val HOST = "127.0.0.1"
  private val PORT = 4002       // 4002 GW paper, 7497 TWS paper
  private val CLIENT_ID = 1
  private val SYMBOL = "CL"
  private val EXCHANGE = "NYMEX"
  private val CURRENCY = "USD"

  def main(args: Array[String]): Unit = {
    val signal = new EJavaSignal()
    val done   = new CountDownLatch(1)
    @volatile var client: EClientSocket = null
    val cds = ListBuffer.empty[ContractDetails]

    def futQuery(): Contract = {
      val c = new Contract
      c.symbol(SYMBOL); c.secType("FUT"); c.exchange(EXCHANGE); c.currency(CURRENCY)
      c // month left empty → query all
    }
    def frontMonth(): Option[Contract] = {
      def key(s: String) = Try(s.take(6).toInt).getOrElse(Int.MaxValue)
      cds.sortBy(cd => key(cd.contract.lastTradeDateOrContractMonth)).headOption.map(_.contract)
    }

    val ew = new DefaultEWrapper {
      override def connectAck(): Unit = { client.startAPI(); client.reqIds(-1) }
      override def nextValidId(id: Int): Unit = {
        client.reqMarketDataType(3) // 3 = delayed
        client.reqContractDetails(1001, futQuery())
        println("[ib] querying CL FUT details…")
      }
      override def contractDetails(reqId: Int, cd: ContractDetails): Unit = cds += cd
      override def contractDetailsEnd(reqId: Int): Unit = frontMonth() match {
        case Some(con) =>
          println(s"[ib] front month: ${con.localSymbol} (${con.lastTradeDateOrContractMonth}); subscribing TBT Last…")
          client.reqTickByTickData(3001, con, "Last", 0, false)
        case None =>
          System.err.println("[ib] no months found"); client.eDisconnect(); done.countDown()
      }
      override def tickByTickAllLast(reqId: Int, tickType: Int, time: Long,
                                     price: Double, size: Decimal,
                                     attr: TickAttribLast, exch: String, special: String): Unit = {
        println(f"[TBT] ${new java.util.Date(time*1000)} $exch%-6s price=$price%.4f size=$size")
      }
      override def error(reqId: Int, time: Long, code: Int, msg: String, adv: String): Unit =
        System.err.println(s"[err] code=$code msg=$msg")

      override def connectionClosed(): Unit = { println("[ib] closed"); done.countDown() }
    }

    client = new EClientSocket(ew, signal)
    client.eConnect(HOST, PORT, CLIENT_ID)
    val reader = new EReader(client, signal); reader.start()
    new Thread(() => while (client.isConnected) { signal.waitForSignal(); reader.processMsgs() }, "ib-reader").start()
    done.await()
  }
}
