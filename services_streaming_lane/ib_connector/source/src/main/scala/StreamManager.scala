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



abstract class  StreamManager(api:ApiHandler) {

	// collects stdout/stderr from the portal process

	var webSocket :SyncWebSocket=api.establishWebSocket()

	var heartBeatTimestamp:Long =System.currentTimeMillis()

	protected  val webSocketLock: AnyRef = new AnyRef

	protected val heartBeatTimestampLock :AnyRef = new AnyRef

	implicit val ec : ExecutionContext= CommonUtils.ec
	


	/**
	  * subscribes or unsubscribes based on symbol universe and pod symbol shardin logic
	  * or requests in a concurrent set from the startReader function, the implementation is 
	  * responsible for holding the necessary information to perform this task, in addition
	  * it restarts the feeds for the conIds in the respective Set.
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

		val ws = webSocketLock.synchronized{
			webSocket
		}

		if (now - heartBeatTimestampOld > timeIntervall) {
			println("!!!!!!!!!!!portal seems to have an issue")
			webSocketLock.synchronized {
				val ws  = webSocket

				
				ws.close()
				webSocket = api.establishWebSocket()
				heartBeatTimestampLock.synchronized{
					heartBeatTimestamp=System.currentTimeMillis()
				}
				Thread.sleep(4000L)
				
			}
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
			webSocket
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
		println("!!!!!!!!!!!reader seems to have an issue")
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
		CommonUtils.scheduleAtFixedRate(schedulingIntervall){
			println("next lifecycle starts")
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
