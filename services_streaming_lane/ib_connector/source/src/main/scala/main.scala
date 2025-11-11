package src.main.scala

import ujson._
import sttp.client4.quick._
import sttp.ws.WebSocketFrame
import java.nio.charset.StandardCharsets

object main {
  def main(args: Array[String]): Unit = {

    // trivial implementation for testing: computeShards returns empty map
    val cm = new ConnectionManager {
      def computeShards(): Map[String, List[String]] = Map.empty
    }

    val portalStarted: Boolean = cm.startIbPortal()
    val authenticated: Boolean = cm.authenticate(1)

    println(s"portalStarted = $portalStarted, authenticated = $authenticated")

    // fetch CL futures contracts
    val futReq  = ApiHandler.endpointsMap(EndPoints.FuturesContractCL)
    val futResp = futReq.send()
    val futBody = futResp.body
    val futJson = ujson.read(futBody)
    val clArray = futJson("CL").arr

    // sort by expirationDate ascending and take first 5 months
    val front5   = clArray.sortBy(c => c("expirationDate").num.toLong).take(5)
    val conIds   = front5.map(c => c("conid").num.toLong.toString)
    val expDates = front5.map(c => c("expirationDate").num.toLong)

    println("subscribing CL contracts (first 5 months):")
    conIds.zip(expDates).foreach { case (cid, exp) =>
      println(s"  conId = $cid, expirationDate = $exp")
    }

    // establish websocket (tickle + cookie happens inside)
    ApiHandler.establishWebSocket()
	Thread.sleep(3000L)
    // subscribe to tick-by-tick / streaming market data for these 5 conIds
    conIds.foreach(ApiHandler.subcribetbt)

    // read and print incoming messages
    val ws = ApiHandler.getWebSocket()

    println(ws.isOpen())

    while (true) {
      val frame: WebSocketFrame = ws.receive()
      frame match {
        case WebSocketFrame.Text(text, _, _) =>
          println(s"TEXT  => $text")

        case WebSocketFrame.Binary(bytes, _, _) =>
          val s = new String(bytes, StandardCharsets.UTF_8)
          println(s"BINARY => $s")

        case WebSocketFrame.Close(code, reason) =>
          println(s"CLOSE  => code=$code reason=$reason")
          sys.exit(0)

        case WebSocketFrame.Ping(_) =>
          println("PING")

        case WebSocketFrame.Pong(_) =>
          println("PONG")

        case _ =>
          println("we got something else ")
      }
    }
  }
}
