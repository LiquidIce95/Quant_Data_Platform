package src.main.scala

import sttp.client4.Response
import sttp.client4.Request
import sttp.client4.ws.SyncWebSocket
import sttp.client4.DefaultSyncBackend
import sttp.client4.ws.sync._
import scala.annotation.meta.field
import sttp.ws.WebSocket
import sttp.ws.WebSocketFrame
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.sys.process.ProcessLogger
import java.io.File
import scala.sys.process._
import sttp.client4.quick._
import org.apache.kafka.common.protocol.types.Field.Bool
import scala.util.Try
import scala.util.Success
import scala.util.Failure


sealed abstract class EndPoints
object EndPoints {
	case object Tickle extends EndPoints
	case object AuthStatus extends EndPoints
	case object FuturesContractNG extends EndPoints
	case object FuturesContractCL extends EndPoints
	case object tbtTopic extends EndPoints
	case object l2Topic extends EndPoints
}

trait ApiHandler {
	val numberOfFrontMonths = 10
	val baseUrl: String = "https://localhost:5000/v1/api"
	val webSocketUrl: String = "wss://localhost:5000/v1/api/ws"
	var symbolUniverse :Vector[(Long,String,String)] = Vector.empty
	private var sessionCookieOpt: Option[String] = None

	// Map each endpoint to a prepared quickRequest
	val endpointsMap: Map[EndPoints, Request[String]] = Map(
		EndPoints.Tickle             -> quickRequest.get(uri"$baseUrl/tickle"),
		EndPoints.AuthStatus         -> quickRequest.get(uri"$baseUrl/iserver/auth/status"),
		EndPoints.FuturesContractNG  -> quickRequest.get(uri"$baseUrl/trsrv/futures?symbols=NG"),
		EndPoints.FuturesContractCL  -> quickRequest.get(uri"$baseUrl/trsrv/futures?symbols=CL")
	)

	/**
	  * subscribees to the market data topic on the opened websocket for tick by tick streaming data
	  * the field Ids are explained here : https://www.interactivebrokers.com/campus/ibkr-api-page/cpapi-v1/#market-data-fields
	  * @param conId The contract Id from the asset (Future)
	  */
	def subscribetbt(conId: String, webSocketOpt: Option[SyncWebSocket]): Unit = {
		assert(webSocketOpt != None)
		val ws = webSocketOpt.get
		val fieldsJson = """{"fields":["31","55","6008","6509","7059","7697"]}"""
		val msg = s"smd+$conId+$fieldsJson"
		ws.send(WebSocketFrame.text(msg))
	}

	/**
	  * unsubscribes from the market data topic on the opened websocket for tick by tick streaming data
	  *
	  * @param conId The contract Id from the asset (Future)
	  */
	def unsubscribetbt(conId: String, webSocketOpt: Option[SyncWebSocket]): Unit = {
		assert(webSocketOpt != None)
		assert(conId!="")
		val ws = webSocketOpt.get
		val msg = s"umd+$conId+{}"
		ws.send(WebSocketFrame.text(msg))
	}

	/**
	  * subscribes to a topic on the opened websocket for l2 DOM market data stream
	  *
	  * @param conId the contract for whihc to subscribe
	  */
	def subscribeL2(accId:String,conId: String,webSocketOpt : Option[SyncWebSocket]): Unit = {
		assert(webSocketOpt!=None)
		assert(conId!="")
		assert(accId!="")
		val ws = webSocketOpt.get 
		val msg = s"sbd+$accId+$conId"
		ws.send(WebSocketFrame.text(msg))
	}

	/**
	  * unsubscribes from thee topic on the opened websocket for l2 DOM market data stream
	  *
	  * @param conId the contract for which to subscribe
	  */
	def unsubscribeL2(accId:String,conId: String,webSocketOpt:Option[SyncWebSocket]): Unit = {
		assert(webSocketOpt!=None)
		assert(accId!="")
		assert(conId!="")
		val ws = webSocketOpt.get 
		val msg = s"ubd+$accId+$conId"
		ws.send(WebSocketFrame.text(msg))

	}

	/**
	  * Call /tickle once, parse the JSON body, and extract the "session" value.
	  * We then store this session string and later use it as the cookie value.
	  *
	  * Assumes the Client Portal Gateway is running and the user is authenticated.
	  */
	private def fetchAndStoreCookie(): Unit = {
		val tickleReq: Request[String] = endpointsMap(EndPoints.Tickle)
		val resp: Response[String] = tickleReq.send()

		val body = resp.body
		val json = ujson.read(body)
		val sessionId = json("session").str

		sessionCookieOpt = Some(sessionId)
	}

	/**
	  * creates a websocket object and stores it as an attribute, returns the web socket object
	  */
	def establishWebSocket(): Option[SyncWebSocket] = {
		fetchAndStoreCookie()
		assert(sessionCookieOpt != None)
		val backend = DefaultSyncBackend()

		val ws: SyncWebSocket =
			basicRequest
				.get(uri"$webSocketUrl")
				.header("Cookie", f"api={\"session\":\"${sessionCookieOpt.get}\"}")
				.response(asWebSocketAlwaysUnsafe)
				.send(backend)
				.body

		Some(ws)
	}

	/**
	  * computes the total symbol that we are interested in streming
	  *
	  * @return returns triplets denoting contract id expiry date and contract month 
	  * sorted ascending by contract month
	  */
	def computeSymbolUniverse():Vector[(Long,String,String)]={
		if (symbolUniverse != Vector.empty){
			symbolUniverse
		}
		// CL
		val clReq   = endpointsMap(EndPoints.FuturesContractCL)
		val clResp  = clReq.send()
		val clBody  = clResp.body
		val clJson  = ujson.read(clBody)
		val clArray = clJson("CL").arr

		val clFront5: Vector[(Long, String,String)] =
			clArray
				.sortBy(c => c("expirationDate").num.toLong)
				.take(numberOfFrontMonths)
				.map { c =>
					val conId = c("conid").num.toLong
					val exp   = c("expirationDate").num.toLong.toString
					val symbol = "CL"
					(conId, exp, symbol)
				}
				.toVector.sortBy(x=>x._2)

		// NG
		val ngReq   = endpointsMap(EndPoints.FuturesContractNG)
		val ngResp  = ngReq.send()
		val ngBody  = ngResp.body
		val ngJson  = ujson.read(ngBody)
		val ngArray = ngJson("NG").arr

		val ngFront5: Vector[(Long, String,String)] =
			ngArray
				.sortBy(c => c("expirationDate").num.toLong)
				.take(numberOfFrontMonths)
				.map { c =>
					val conId = c("conid").num.toLong
					val exp   = c("expirationDate").num.toLong.toString
					val symbol = "NG"
					(conId, exp, symbol)
				}
				.toVector

            symbolUniverse=(clFront5 ++ ngFront5)
			symbolUniverse
	}.sortBy(x=>x._2)

	// portal manager starts here

	val portalOutput: StringBuilder = new StringBuilder


	protected val outputLock : AnyRef = new AnyRef
    // logger that appends all lines to portalOutput
	val portalLogger: ProcessLogger = ProcessLogger(
		(line: String) => {
			portalOutput.append(line).append('\n')
		},
		(err: String) => {
			portalOutput.append(err).append('\n')
		}
	)


	@volatile var portalProcFuture: Future[Unit] = Future.successful(())

    private def hasFatalErrorInLogs: Boolean = {
		outputLock.synchronized{
			val s = portalOutput.toString
			s.contains("Server listen failed") || s.contains("ERROR") || s.contains("Exception")
		}
	}


	/**
	  * computes the userId we need to use from the keyVault, based on this pods Id and nubmer of
	  * replicas in the cluster namespace
	  *
	  * @return
	  */
	def computeUser():Int

	def isHealthy():Boolean={
		val running = outputLock.synchronized{
			portalOutput.toString.contains("Open https://localhost:5000 to login") && !portalOutput.toString.contains("Server listen failed Address already in use")
		}
		val authed= Try {
			val statusReq = endpointsMap(EndPoints.AuthStatus)
			val resp = statusReq.send()
			if (resp.code.isSuccess) {
				val json = ujson.read(resp.body)
				json.obj.get("authenticated") match {
					case Some(v) => v.bool
					case None    => false
				}
			} else {
				false
			}
		} match {
			case Success(b) => b
			case Failure(_) => false
		}
		!hasFatalErrorInLogs && running && !portalProcFuture.isCompleted && authed
	}

	/**
	  * starts the ib web api client for this pod with a bash command.
	  * cheecks the console output for "Open https://localhost:5000 to login"
	  *
	  * @return true if the string was present in the console output, false otherwise
	  * 
	  */
	def startIbPortal(): Boolean = {
		outputLock.synchronized{
			val workDir = new File("./clientportal.gw")


			// kill all processes listening on port 5000 including any left overs from old trials
			val cmdKill = Seq(
				"bash",
				"-lc",
				"lsof -ti:5000 | xargs -r kill"
			).!
			portalOutput.clear()

			Thread.sleep(6000L)
			val cmd = Seq(
				"bash",
				"-lc",
				"./bin/run.sh root/conf.yaml"
			)
			portalProcFuture = Future {
				Process(cmd,workDir).!(portalLogger)
				()
			}

			

			val timeout:Long = 50000L
			val deadline = System.currentTimeMillis()+timeout
			var portalStarted = false

			while(!portalStarted && System.currentTimeMillis()<deadline){
				Thread.sleep(3000L)
				portalStarted = portalOutput.toString.contains("Open https://localhost:5000 to login") && !portalOutput.toString.contains("Server listen failed Address already in use")
				
			}
			portalStarted
		}
	}
	

	/**
	  * runs the python authenticator without 2fa script and checks if we got 
	  * "authenticated":true in the console output
	  *	if you need to call the authenticator that also handles 2FA then you need to change the bash command
	  * @param userId the user to use for using the api 1 or two
	  * @return true if the string was present in the console output and false otherwise
	  */
	def authenticate(userId: Int = 1): Boolean = {
		val workDir = new File(".")

		val cmd = Seq(
			"bash",
			"-lc",
			s"source /opt/venv/bin/activate && python3 authenticator_no_2fa.py $userId"
		)

		val output = new StringBuilder

		val logger = ProcessLogger(
			(line: String) => {
				output.append(line).append('\n')
			},
			(err: String) => {
				output.append(err).append('\n')
			}
		)

		Process(cmd, workDir).!(logger)
		Thread.sleep(10000L)
		output.toString.contains("\"authenticated\":true")
	}

    /**
     * checks if the client portal is running, if not we start it and authenticate again
     * this will be called by the startStream function inn StreamManager before ConnectionManager apply
     * @return a tuple where the first component is the result of startIbPortal and the second 
     * from authenticate
     * thorws an exception if its unable to start portal and authenticate in this case we need to restart pod
     */
    def startPortalLifeCycleManagement():Unit={
        // 1) Try to check current auth status via Web API
        val healthy = outputLock.synchronized{
			isHealthy()
		}
        if (healthy) {
			// we need to authenticte to keep session valid
			authenticate(computeUser())
            ()
        } else {
			if (startIbPortal()) (authenticate(computeUser()))
        }
    }

	def startApi():Unit={
		val lifeCyclePeriod:Long = 6000L
		
        val timeout:Long = 20000L
        val deadline = System.currentTimeMillis()+timeout

		CommonUtils.scheduleAtFixedRate(lifeCyclePeriod) {
			startPortalLifeCycleManagement()
		}

		while(!isHealthy()&& System.currentTimeMillis()<deadline){
        	Thread.sleep(3000L)
        }

        if(!isHealthy()){
            throw new Exception("we cannot start the api, need to restart pod")
        }

	}

}
