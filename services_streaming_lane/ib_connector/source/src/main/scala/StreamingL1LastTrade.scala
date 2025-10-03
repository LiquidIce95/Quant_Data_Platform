import com.ib.client._
import java.util.concurrent.CountDownLatch
import scala.collection.mutable.ListBuffer
import scala.util.Try

object StreamingL1LastTrade {
  // ---- config ----
  private val HOST = "127.0.0.1"   // IB Gateway/TWS
  private val PORT = 4002          // 4002=GW paper, 7497=TWS paper
  private val CLIENT_ID = 1
  private val SYMBOL = "CL"
  private val EXCHANGE = "NYMEX"
  private val CURRENCY = "USD"
  private val REQ_CD = 1001
  private val REQ_SNAP = 5002
  private val REQ_STREAM = 5001

  def main(args: Array[String]): Unit = {
    val sig = new EJavaSignal()
    val done = new CountDownLatch(1)
    val cds = ListBuffer.empty[ContractDetails]
    @volatile var c: EClientSocket = null
    @volatile var sub = false

    def futQuery: Contract = { val k = new Contract; k.symbol(SYMBOL); k.secType("FUT"); k.exchange(EXCHANGE); k.currency(CURRENCY); k }
    def front: Option[Contract] =
      cds.sortBy(cd => Try(cd.contract.lastTradeDateOrContractMonth.take(6).toInt).getOrElse(Int.MaxValue))
        .headOption.map(_.contract)

    val ew = new DefaultEWrapper {
      override def connectAck(): Unit = { c.startAPI(); c.reqIds(-1) }
      override def nextValidId(id: Int): Unit = { c.reqMarketDataType(3); c.reqContractDetails(REQ_CD, futQuery) } // delayed
      override def contractDetails(r: Int, cd: ContractDetails): Unit = cds += cd
      override def contractDetailsEnd(r: Int): Unit = if (!sub) front.foreach { con =>
        println(s"[ib] front: ${con.localSymbol} (${con.lastTradeDateOrContractMonth})")
        c.reqMarketDataType(3)                                     // back to delayed stream
        c.reqMktData(REQ_STREAM, con, "233", false, false, null)   // RTVolume included
        sub = true
      }
      override def marketDataType(tid: Int, t: Int): Unit = println(s"[mdType] $t (1=real,2=frozen,3=delayed,4=delayed-frozen)")
      override def tickPrice(tid: Int, f: Int, p: Double, a: TickAttrib): Unit =
        println(s"[tickPrice][$tid] ${TickType.getField(f)} = $p")
      override def tickSize(tid: Int, f: Int, s: Decimal): Unit =
        println(s"[tickSize][$tid] ${TickType.getField(f)} = $s")
      override def tickString(tid: Int, f: Int, v: String): Unit =
        println(s"[tickString][$tid] ${TickType.getField(f)} = $v")
      override def error(reqId: Int, time: Long, code: Int, msg: String, adv: String): Unit =
        System.err.println(s"[err] $code $msg (reqId=$reqId)")
      override def connectionClosed(): Unit = { println("[ib] closed"); done.countDown() }
    }

    c = new EClientSocket(ew, sig)
    c.eConnect(HOST, PORT, CLIENT_ID)
    val r = new EReader(c, sig); r.start()
    new Thread(() => while (c.isConnected) { sig.waitForSignal(); r.processMsgs() }, "ib-reader").start()
    done.await() // Ctrl+C to stop
  }
}
