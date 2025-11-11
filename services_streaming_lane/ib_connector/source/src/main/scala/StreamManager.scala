package src.main.scala

import scala.sys.process._
import java.io.File
import sttp.client4.ws.SyncWebSocket
import sttp.ws.WebSocketFrame
import java.nio.charset.StandardCharsets
import scala.util.control.Breaks
import upickle.core.LinkedHashMap
import scala.concurrent._
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import sttp.client4.quick._



trait StreamManager {

	var symbolShards: Map[String, List[String]] = Map.empty

	/**
	  * Long is the contract Id form interactive brokers
	  *	Stirng is the expiry date
	  * 
	  */
	var symbolUniverse: List[(Long,String)] = Nil

	// collects stdout/stderr from the portal process
	val portalOutput: StringBuilder = new StringBuilder

	var webSocketOpt :Option[SyncWebSocket]=None

	var heartBeatTimestamp:Long =0L

	private val webSocketLock: AnyRef = new AnyRef

	private val heartBeatTimestampLock :AnyRef = new AnyRef

	implicit val ec: ExecutionContext = ExecutionContext.global
	val scheduler = Executors.newSingleThreadScheduledExecutor()

	def scheduleAtFixedRate(intervalMs: Long)(f: => Unit): Unit = {
		def loop(): Unit = {
			Future(f).onComplete(_ =>
				scheduler.schedule(new Runnable { def run(): Unit = loop() },
					intervalMs, TimeUnit.MILLISECONDS)
			)
		}
		loop()
	}

	// logger that appends all lines to portalOutput
	val portalLogger: ProcessLogger = ProcessLogger(
		(line: String) => {
			portalOutput.append(line).append('\n')
		},
		(err: String) => {
			portalOutput.append(err).append('\n')
		}
	)

	/**
	  * starts the ib web api client for this pod with a bash command.
	  * cheecks the console output for "Open https://localhost:5000 to login"
	  *
	  * @return true if the string was present in the console output, false otherwise
	  * 
	  */
	def startIbPortal(): Boolean = {
		val workDir = new File("/work/services_streaming_lane/ib_connector/source/clientportal.gw")

		val cmd = Seq(
			"bash",
			"-lc",
			"./bin/run.sh root/conf.yaml"
		)

		// start portal asynchronously; do not wait for termination
		val proc = Process(cmd, workDir).run(portalLogger)

		// give the gateway a bit of time to print its startup banner
		Thread.sleep(3000L)

		portalOutput.toString.contains("Open https://localhost:5000 to login") &&
			!portalOutput.toString.contains("Server listen failed Address already in use")
	}

	/**
	  * runs the python authenticator without 2fa script and checks if we got 
	  * "authenticated":true in the console output
	  *
	  * @param userId
	  * @return true if the string was present in the console output and false otherwise
	  */
	def authenticate(userId: Int = 1): Boolean = {
		val workDir = new File("/work/services_streaming_lane/ib_connector/source")

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
		Thread.sleep(17000L)
		output.toString.contains("\"authenticated\":true")
	}

	/**
	  * has no arguments because for testing we provide a constant function and 
	  * for produciton the function must use kubernets api to get peers the function
	  * that performs the sharding is tested seperately
	  * 
	  * @return a map that relates the peer names to the list of symbols / shard it must process
	  */
	def computeShards(): Map[String, List[String]]


	/**
	  * subscribes or unsubscribes based on symbol universe and pod symbol shard
	  */
	def manageConnections():Unit


	/**
	  * computes the userId we need to use from the keyVault, based on this pods Id and nubmer of
	  * replicas in the cluster namespace
	  *
	  * @return
	  */
	def computeUser():Int

	/**
	  * This function will be called upon receival of a smd topic frame 
	  *
	  * @param message decoded raw text string
	  */
	def onSmdFrame(message:LinkedHashMap[String,ujson.Value]):Unit 


	/**
	  * This function will be called upon receival of a sbd topic frame
	  *
	  * @param message decoded raw text string
	  */
	def onSbdFrame(message:LinkedHashMap[String,ujson.Value]):Unit

	/**
	  * starts the lifecycle management of the Socket,checks if the last heartbeat is no longer than timefintervall seconds
	  * @param timeIntervall milliseconds, after whihc timedelta should we consider the socket connection to be broken
	  */
	def socketLifeCycleManagement(timeIntervall: Long): Unit = {

		webSocketLock.synchronized{
			assert(webSocketOpt != None, "webSocketOpt must be defined for socket lifecycle management")
		}
		assert(timeIntervall>=1000L,"what are you expecting? timeIntervall is too short")
		// if heartbeat too old (or never received), consider connection broken, dirty reads are not bad here
		val heartBeatTimestampOld = heartBeatTimestampLock.synchronized{
			heartBeatTimestamp
		}
		
		val now = System.currentTimeMillis()

		if (heartBeatTimestampOld == 0L || now - heartBeatTimestampOld > timeIntervall) {
			webSocketLock.synchronized {
				val ws  = webSocketOpt.get

				
				ws.close()
				webSocketOpt = ApiHandler.establishWebSocket()
				assert(webSocketOpt!=None, "we have a problem with reinitializing the websocket connection")
				heartBeatTimestampLock.synchronized{
					heartBeatTimestamp=0L
				}
				Thread.sleep(3000L)
				
			}
		}
		
	}

	/**
	  * checks if the client portal is running, if not we start it and authenticate again
	  * @return a tuple where the first component is the result of startIbPortal and the second 
	  * from authenticate
	  */
	def portalLifeCycleManagement():(Boolean,Boolean)={
		import scala.util.{Try, Success, Failure}

		// 1) Try to check current auth status via Web API
		val statusReq = ApiHandler.endpointsMap(EndPoints.AuthStatus)

		val isAuthOk: Boolean =
			Try {
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

		// 2) If portal is reachable and authenticated, we’re done
		if (isAuthOk) {
			(false, true)
		} else {
			// 3) Otherwise: try to (re)start portal and re-authenticate
			val portalStarted: Boolean = startIbPortal()
			val userId: Int             = computeUser()
			val authed: Boolean         = authenticate(userId)
			(portalStarted, authed)
		}
	}

	/**
	  * starts the Reader, subscribes to the provided symbol list TODO updates the hearbeat attribute upon receival of the heartbeat, 
	  * calls onSmdFrame when a frame from topic 'smd' is received
	  * calls onSbdFrame when a frame from topic 'sbd' is received
	  * apparently the web api only sends binnary frames and those are json formatted
	  */
	def startReader():Unit={
		val ws : SyncWebSocket = webSocketLock.synchronized{
			assert(webSocketOpt!=None)
			webSocketOpt.get
		}
		while(ws.isOpen()){
			val frame: WebSocketFrame = ws.receive()
			frame match {
				case WebSocketFrame.Binary(bytes, _, _) =>
					val s = new String(bytes, StandardCharsets.UTF_8)
					val parsed = ujson.read(s).obj
					val topic = parsed("topic").str

					topic match {
						case "smd" => onSmdFrame(parsed)
						case "sbd" => onSbdFrame(parsed)
						case "system" => parsed.get("hb").foreach( 
							{ hbTimestamp =>
								heartBeatTimestampLock.synchronized{
									heartBeatTimestamp=hbTimestamp.num.toLong
								}
							}
						)
					}
					
				case WebSocketFrame.Close(code, reason) =>
				println(s"CLOSE  => code=$code reason=$reason")
				Breaks

				case WebSocketFrame.Ping(_) =>
				println("PING")

				case WebSocketFrame.Pong(_) =>
				println("PONG")

				case _ =>
				println("we got something else ")
			}
		}
		heartBeatTimestampLock.synchronized{
			heartBeatTimestamp=0L
		}

	}

	def startStream():Unit={
		val userId = computeUser()
		startIbPortal()
		
		assert(authenticate(userId),f"Not able to authenticate with userId ${userId}")

		// CL
		val clReq   = ApiHandler.endpointsMap(EndPoints.FuturesContractCL)
		val clResp  = clReq.send()
		val clBody  = clResp.body
		val clJson  = ujson.read(clBody)
		val clArray = clJson("CL").arr

		val clFront5: List[(Long, String)] =
			clArray
				.sortBy(c => c("expirationDate").num.toLong)
				.take(5)
				.map { c =>
					val conId = c("conid").num.toLong
					val exp   = c("expirationDate").num.toLong.toString
					(conId, exp)
				}
				.toList

		// NG
		val ngReq   = ApiHandler.endpointsMap(EndPoints.FuturesContractNG)
		val ngResp  = ngReq.send()
		val ngBody  = ngResp.body
		val ngJson  = ujson.read(ngBody)
		val ngArray = ngJson("NG").arr

		val ngFront5: List[(Long, String)] =
			ngArray
				.sortBy(c => c("expirationDate").num.toLong)
				.take(5)
				.map { c =>
					val conId = c("conid").num.toLong
					val exp   = c("expirationDate").num.toLong.toString
					(conId, exp)
				}
				.toList

		symbolUniverse = clFront5 ++ ngFront5

		symbolShards = computeShards()


		val schedulingIntervall :Long = 2000L

		val heartBeatTolerance :Long = 60000L

		webSocketOpt = ApiHandler.establishWebSocket()

		Thread.sleep(3000L)

		// start the reader in its own Future in async, we want to continue from the code 
		var readerFuture : Future[Unit] = Future {
			startReader()
		}
		// leave here enough time for the first heartbeat to arrive
		Thread.sleep(2000L)

		//IMPORTANT socketLifeCycleManagement and startReader share webSocketOpt , so we must use a lock
		scheduleAtFixedRate(schedulingIntervall){
			portalLifeCycleManagement()
			socketLifeCycleManagement(heartBeatTolerance)
			symbolShards = computeShards()
			manageConnections()
			// if the reader is not running anymore we need to restat it
			if (readerFuture.isCompleted){
				readerFuture = Future {
					startReader()
				}
			}
		}




	}

}
