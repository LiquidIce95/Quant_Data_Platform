package src.main.scala

import sttp.client4.quick._
import sttp.client4.Response
import sttp.client4.quick
import sttp.client4.Request
import sttp.client4.ws.SyncWebSocket
import sttp.client4.DefaultSyncBackend
import sttp.client4.ws.sync._
import scala.annotation.meta.field
import sttp.ws.WebSocket
import sttp.ws.WebSocketFrame



sealed abstract class EndPoints
object EndPoints {
	case object Tickle extends EndPoints
	case object AuthStatus extends EndPoints
    case object FuturesContractNG extends EndPoints
    case object FuturesContractCL extends EndPoints
    case object tbtTopic extends EndPoints
    case object l2Topic extends EndPoints
}

object ApiHandler {

	val baseUrl: String = "https://localhost:5000/v1/api"
    val webSocketUrl : String = "wss://localhost:5000/v1/api/ws"
    private var webSocketOpt: Option[SyncWebSocket] = None
    private var sessionCookieOpt: Option[String] = None


	// Map each endpoint to a prepared quickRequest
	val endpointsMap: Map[EndPoints, Request[String]] = Map(
		EndPoints.Tickle     -> quickRequest.get(uri"$baseUrl/tickle"),
		EndPoints.AuthStatus -> quickRequest.get(uri"$baseUrl/iserver/auth/status"),
    EndPoints.FuturesContractNG -> quickRequest.get(uri"$baseUrl/trsrv/futures?symbols=NG"),
    EndPoints.FuturesContractCL -> quickRequest.get(uri"$baseUrl/trsrv/futures?symbols=CL")
	)

    /**
      * subscribees to the market data topic on the opened websocket for tick by tick streaming data
      * the field Ids are explained here : https://www.interactivebrokers.com/campus/ibkr-api-page/cpapi-v1/#market-data-fields
      * @param conId The contract Id from the asset (Future)
      */
    def subcribetbt(conId:String):Unit={
        assert(webSocketOpt!=None)
        val ws = getWebSocket()
        val fieldsJson = """{"fields":["31","55","6008","6509","7059","7697"]}"""
        val msg = s"smd+$conId+$fieldsJson"
        ws.send(WebSocketFrame.text(msg))
    }

    /**
      * unsubscribes from the market data topic on the opened websocket for tick by tick streaming data
      *
      * @param conId The contract Id from the asset (Future)
      */
    def unsubscribetbt(conId:String):Unit={
        assert(webSocketOpt!=None)
        val ws = getWebSocket()
        val msg = s"umd+$conId+{}"
        ws.sendText(msg)
    }

    /**
      * subscribes to a topic on the opened websocket for l2 DOM market data stream
      *
      * @param conId the contract for whihc to subscribe
      */
    def subscribeL2(conId:String):Unit={

    }

    /**
      * unsubscribes from thee topic on the opened websocket for l2 DOM market data stream
      *
      * @param conId the contract for which to subscribe
      */    
    def unsubscribeL2(conId:String):Unit={

    }

    /**
	  * Call /tickle once, parse the JSON body, and extract the "session" value.
	  * We then store this session string and later use it as the cookie value.
	  *
	  * Assumes the Client Portal Gateway is running and the user is authenticated.
	  */
	private def fetchAndStoreCookie(): Unit = {
		val tickleReq: Request[String] = endpointsMap(EndPoints.Tickle)
		val resp: Response[String]     = tickleReq.send()

		val body      = resp.body
		val json      = ujson.read(body)
		val sessionId = json("session").str

		sessionCookieOpt = Some(sessionId)
	}

    /**
      * creates a websocket object and stores it as an attribute, returns the web socket object
      */
  def establishWebSocket():Unit={
        fetchAndStoreCookie()
        assert(sessionCookieOpt!=None)
        val backend = DefaultSyncBackend()

        val ws: SyncWebSocket =
          basicRequest
            .get(uri"$webSocketUrl")
            .header("Cookie",f"api={\"session\":\"${sessionCookieOpt.get}\"}")
            .response(asWebSocketAlwaysUnsafe)
            .send(backend)
            .body
      
        webSocketOpt = Some(ws)
    }

    /**
      * 
      *
      * @return the webSocket object
      */
  def getWebSocket():SyncWebSocket ={
        webSocketOpt.get
  }


}
