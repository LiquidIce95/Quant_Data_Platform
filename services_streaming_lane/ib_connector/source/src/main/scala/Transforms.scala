package src.main.scala

import com.ib.client.{Decimal, TickAttribLast}
import java.time.Instant

object Transforms {
  private val SourceSystem = "interactive_brokers_tws_api"

  private def nowMillis: Long = Instant.now().toEpochMilli

  // Minimal JSON string escaper (keeps everything dependency-free)
  private def esc(s: String): String = {
    if (s == null) "" else {
      val b = new StringBuilder(s.length + 16)
      s.foreach {
        case '"'  => b.append("\\\"")
        case '\\' => b.append("\\\\")
        case '\b' => b.append("\\b")
        case '\f' => b.append("\\f")
        case '\n' => b.append("\\n")
        case '\r' => b.append("\\r")
        case '\t' => b.append("\\t")
        case c if c < ' ' => b.append(f"\\u${c.toInt}%04x")
        case c   => b.append(c)
      }
      b.toString()
    }
  }

  private def decToString(d: Decimal): String =
    if (d == null) "0" else d.toString

  // Tick-by-tick "Last" → JSON (string)
  def tickLastJson(
    reqId: Int,
    tickType: Int,
    time: Long,
    price: Double,
    size: Decimal,
    attr: TickAttribLast,
    exchange: String,
    specialConditions: String,
    tradingSymbol: String
  ): String = {
    val extraction = nowMillis
    val pastLimit  = if (attr == null) false else attr.pastLimit()
    val lastAbove  = if (attr == null) false else attr.unreported()
    val sizeStr    = decToString(size)

    s"""{"type":"tickLast","trading_symbol":"${esc(tradingSymbol)}","extraction_time":$extraction,"source_system":"$SourceSystem","req_id":$reqId,"tick_type":$tickType,"ib_time":$time,"price":$price,"size":"$sizeStr","attrib":{"past_limit":$pastLimit,"last_was_above_tick":$lastAbove},"exchange":"${esc(exchange)}","special_conditions":"${esc(specialConditions)}"}"""
  }

  // L2 book update → JSON (string)
  def l2Json(
    reqId: Int,
    position: Int,
    marketMaker: String,
    update_time: Long,
    side: Int,
    price: Double,
    size: Double,
    isSmartDepth: Boolean,
    tradingSymbol: String
  ): String = {
    val extraction = nowMillis

    s"""{"type":"l2","trading_symbol":"${esc(tradingSymbol)}","extraction_time":$extraction,"source_system":"$SourceSystem","req_id":$reqId,"position":$position,"market_maker":"${esc(marketMaker)}","update_time":$update_time,"side":$side,"price":$price,"size":"$size","is_smart_depth":$isSmartDepth}"""
  }
}
