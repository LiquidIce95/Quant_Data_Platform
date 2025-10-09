import com.ib.client._
import java.util.concurrent.CountDownLatch
import scala.collection.mutable.ListBuffer
import scala.collection.mutable
import scala.util.Try

object StreamingL1LastTrade {
  // ---- config ----
  private val HOST = "127.0.0.1"
  private val PORT = 4002            // 4002=GW paper, 7497=TWS paper
  private val CLIENT_ID = 1
  private val SYMBOL = "CL"
  private val SYMBOL_NG = "NG"
  private val EXCHANGE = "NYMEX"
  private val CURRENCY = "USD"
  private val REQ_CD = 1001
  private val REQ_SNAP = 5002
  private val REQ_STREAM = 5001

  // reqId -> contract code
  private val reqIdToCode = mutable.Map.empty[Int, String]

  def main(args: Array[String]): Unit = {
    val sig = new EJavaSignal()
    val done = new CountDownLatch(1)

    val cdsCL = ListBuffer.empty[ContractDetails]
    val cdsNG = ListBuffer.empty[ContractDetails]

    @volatile var c: EClientSocket = null
    @volatile var sub = false
    @volatile var started = false
    var ends = 0

    def futQuery(sym: String): Contract = {
      val k = new Contract
      k.symbol(sym); k.secType("FUT"); k.exchange(EXCHANGE); k.currency(CURRENCY)
      k
    }

    // FIRST n unique months from the given buffer (no shadowing!)
    def fronts(n: Int, buf: ListBuffer[ContractDetails]): List[Contract] = {
      import scala.collection.mutable.{ListBuffer => LB, LinkedHashSet}
      val seen = LinkedHashSet.empty[String]
      val out  = LB.empty[Contract]
      buf.sortBy(cd => Try(cd.contract.lastTradeDateOrContractMonth.take(6).toInt).getOrElse(Int.MaxValue))
        .foreach { cd =>
          val yyyymm = Try(cd.contract.lastTradeDateOrContractMonth.take(6)).getOrElse("")
          if (yyyymm.nonEmpty && seen.add(yyyymm)) out += cd.contract
          if (out.size >= n) return out.toList
        }
      out.toList
    }

    val ew = new DefaultEWrapper {
      override def connectAck(): Unit = { c.startAPI(); c.reqIds(-1) }

      override def nextValidId(id: Int): Unit = {
        if (started) return
        started = true
        c.reqMarketDataType(3) // delayed

        c.reqContractDetails(REQ_CD,     futQuery(SYMBOL))
        println(s"[ib] reqContractDetails($REQ_CD) for $SYMBOL …")

        c.reqContractDetails(REQ_CD + 1, futQuery(SYMBOL_NG))
        println(s"[ib] reqContractDetails(${REQ_CD + 1}) for $SYMBOL_NG …")
      }

      // route each detail to the correct buffer by reqId
      override def contractDetails(r: Int, cd: ContractDetails): Unit = {
        if (r == REQ_CD) cdsCL += cd
        else if (r == REQ_CD + 1) cdsNG += cd
        else {
          // fallback based on symbol if ever needed
          if (cd.contract.symbol == SYMBOL) cdsCL += cd
          else if (cd.contract.symbol == SYMBOL_NG) cdsNG += cd
        }
      }

      // wait until BOTH details have ended, then subscribe once
      override def contractDetailsEnd(r: Int): Unit = if (!sub) {
        ends += 1
        if (ends < 2) return

        val xsCL = fronts(8, cdsCL)
        val xsNG = fronts(8, cdsNG)
        val xs   = xsCL ++ xsNG // CL first, then NG (deterministic)

        // implement sharding here

        if (xs.nonEmpty) {
          println("[ib] fronts: " + xs.map(k => s"${k.localSymbol} (${k.lastTradeDateOrContractMonth})").mkString(", "))
          c.reqMarketDataType(3)
          xs.zipWithIndex.foreach { case (con, i) =>
            val reqId = REQ_STREAM + i
            val code  = Option(con.localSymbol).filter(_.nonEmpty).getOrElse(con.symbol)
            reqIdToCode.update(reqId, code)
            c.reqMktData(reqId, con, "233", false, false, null) // RTVolume
            println(s"[ib] subscribed reqId=$reqId -> $code")
          }
          sub = true
        } else {
          System.err.println("[ib] no months found for CL/NG (permissions/exchange?)")
        }
      }


      // override these functions to test with delayed market data if the setup works
      override def marketDataType(tid: Int, t: Int): Unit =
        println(s"[mdType] $t (1=real,2=frozen,3=delayed,4=delayed-frozen)")

      override def tickPrice(tid: Int, f: Int, p: Double, a: TickAttrib): Unit =
        println(s"[tickPrice][$tid][${reqIdToCode.getOrElse(tid, "?")}] ${TickType.getField(f)} = $p")
      override def tickSize(tid: Int, f: Int, s: Decimal): Unit =
        println(s"[tickSize][$tid][${reqIdToCode.getOrElse(tid, "?")}] ${TickType.getField(f)} = $s")
      override def tickString(tid: Int, f: Int, v: String): Unit =
        println(s"[tickString][$tid][${reqIdToCode.getOrElse(tid, "?")}] ${TickType.getField(f)} = $v")

      override def error(reqId: Int, time: Long, code: Int, msg: String, adv: String): Unit =
        System.err.println(s"[err] $code $msg (reqId=$reqId)")
      override def connectionClosed(): Unit = { println("[ib] closed"); done.countDown() }
    }

    c = new EClientSocket(ew, sig)
    c.eConnect(HOST, PORT, CLIENT_ID)
    val r = new EReader(c, sig); r.start()
    new Thread(() => while (c.isConnected) { sig.waitForSignal(); r.processMsgs() }, "ib-reader").start()
    done.await()
  }
}
