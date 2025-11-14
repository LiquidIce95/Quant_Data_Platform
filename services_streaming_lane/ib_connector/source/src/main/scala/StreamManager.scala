package src.main.scala

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
import scala.util.{Failure, Success}



trait StreamManager {

	// collects stdout/stderr from the portal process

	var webSocketOpt :Option[SyncWebSocket]=None

	var heartBeatTimestamp:Long =System.currentTimeMillis()

	protected  val webSocketLock: AnyRef = new AnyRef

	protected val heartBeatTimestampLock :AnyRef = new AnyRef

	implicit val ec: ExecutionContext = ExecutionContext.global
	val scheduler = Executors.newSingleThreadScheduledExecutor()

	def scheduleAtFixedRate(intervalMs: Long)(f: => Unit): Unit = {
		def loop(): Unit = {
			Future(f).onComplete {
				case Failure(e) =>
					e.printStackTrace()		// or println(s"scheduled task failed: ${e.getMessage}")
					scheduler.schedule(
						new Runnable { def run(): Unit = loop() },
						intervalMs,
						TimeUnit.MILLISECONDS
					)
				case Success(_) =>
					scheduler.schedule(
						new Runnable { def run(): Unit = loop() },
						intervalMs,
						TimeUnit.MILLISECONDS
					)
			}
		}
		loop()
	}


	/**
	  * subscribes or unsubscribes based on symbol universe and pod symbol shardin logic
	  * or requests in a concurrent set from the startReader function, the implementation is 
	  * responsible for holding the necessary information to perform this task
	  */
	def connectionsLifeCycleManagement():Unit

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
		assert(timeIntervall>=1000L,"what are you expecting? timeIntervall is too short")
		// if heartbeat too old (or never received), consider connection broken, dirty reads are not bad here
		val heartBeatTimestampOld = heartBeatTimestampLock.synchronized{
			heartBeatTimestamp
		}
		val now = System.currentTimeMillis()

		val wsOpt = webSocketLock.synchronized{
			webSocketOpt
		}

		if (wsOpt==None){
			webSocketLock.synchronized{
				webSocketOpt = ApiHandler.establishWebSocket()
				assert(webSocketOpt!=None)
			}
		}
		else if (now - heartBeatTimestampOld > timeIntervall) {
			webSocketLock.synchronized {
				val ws  = webSocketOpt.get

				
				ws.close()
				webSocketOpt = ApiHandler.establishWebSocket()
				assert(webSocketOpt!=None, "we have a problem with reinitializing the websocket connection")
				heartBeatTimestampLock.synchronized{
					heartBeatTimestamp=System.currentTimeMillis()
				}
				Thread.sleep(4000L)
				
			}
		}
		
	}

	/**
	  * checks if the client portal is running, if not we start it and authenticate again
	  * @return a tuple where the first component is the result of startIbPortal and the second 
	  * from authenticate
	  */
	def portalLifeCycleManagement():Unit

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
					val topic = parsed("topic").str.split("\\+")
					val topicHead = topic(0)

					topicHead match {
						case "smd" => onSmdFrame(parsed)
						case "sbd" => onSbdFrame(parsed)
						case "system" => parsed.get("hb").foreach( 
							{ hbTimestamp =>
								heartBeatTimestampLock.synchronized{
									heartBeatTimestamp=hbTimestamp.num.toLong
								}
							}
						)
						case _ =>
							println(f"received topic: $topic") 
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
		println("reader terminates")
		heartBeatTimestampLock.synchronized{
			heartBeatTimestamp=0L
		}

	}

	def startStream():Unit={
		val schedulingIntervall :Long = 5000L

		val heartBeatTolerance :Long = 60000L

		// I want to initialize here with a completed future to enter the loop below
		var readerFuture: Future[Unit] = Future.successful(())

		//IMPORTANT socketLifeCycleManagement and startReader share webSocketOpt , so we must use a lock
		scheduleAtFixedRate(schedulingIntervall){
			println("next lifecycle starts")
			println("portalLifeCycle")
			portalLifeCycleManagement()
			println("socketLifeCycle")
			socketLifeCycleManagement(heartBeatTolerance)
			println("connectionsLifeCycle")
			connectionsLifeCycleManagement()
			// if the reader is not running anymore we need to restat it
			if (readerFuture.isCompleted){
				println("reader starting")
				readerFuture = Future {
					startReader()
				}
				readerFuture.failed.foreach { e =>
					e.printStackTrace()
				}
			}
		}




	}

}
