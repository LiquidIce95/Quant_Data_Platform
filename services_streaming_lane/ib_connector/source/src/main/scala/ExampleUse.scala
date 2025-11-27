package src.main.scala

import ujson._
import sttp.client4.quick._
import sttp.ws.WebSocketFrame
import java.nio.charset.StandardCharsets
import sttp.client4.ws.SyncWebSocket
import upickle.core.LinkedHashMap
import scala.sys.process._
import scala.collection.mutable
import org.apache.kafka.shaded.com.google.protobuf.Api
import scala.util.Try
import scala.annotation.tailrec

object ExampleUse {
	
	@tailrec
	def startConnector(retries:Int=0){
		println(f"startConnector called, retries :${retries}")
		if (retries > 5) {
			throw new Exception("We tried to start the connector more than 5 times...")
		}
		Try {				
			val api :ApiHandler = new ApiHandler {
				def computeUser(): Int = {1}
			}

			api.startApi()
			println(f"api started ${api.isHealthy()}")
			object CM extends ConnectionManager(api) {
				def computeShards(): mutable.Map[String, Vector[(Long, String,String)]] = {
					// pick the 2nd CL from symbolUniverse (which was built as CL-front5 ++ NG-front5)
					val pick: Vector[(Long, String, String)] = symbolUniverse.take(1)
					mutable.Map("one" -> pick)
				}
				def determinePodIdentity(): String = "one"
				def getAccountId(): String = {
					val cmd = Seq(
						"bash","-lc",
						"az keyvault secret show --vault-name ibkr-secrets --name ibkr-accountId-1 --query value -o tsv"
					)
					val out = new StringBuilder
					val pl  = ProcessLogger( line => out.append(line), err => out.append(err) )
					Process(cmd).!(pl)
					out.toString.trim
				}
			}

			object SM extends StreamManager(api) {


				def connectionsLifeCycleManagement(): Unit = {
					val ws = webSocketLock.synchronized {
						webSocket
					}
					CM.apply(ws)

				}
				def onSmdFrame(message: LinkedHashMap[String, ujson.Value]): Unit = {
					println(ujson.write(ujson.Obj.from(message)))
				}
				def onSbdFrame(message: LinkedHashMap[String, ujson.Value]): Unit = {
					println(ujson.write(ujson.Obj.from(message)))
				}
			}

			// make sure you ran az login on this container
			SM.startStream()

			// keep the test alive
			Thread.sleep(Long.MaxValue)
		} match {
			case scala.util.Success(_) =>
				() // if we ever get here (e.g. sleep interrupted), just stop recursing
			case scala.util.Failure(e) =>
				println(s"startConnector failed: ${e.getMessage}")
				startConnector(retries + 1)
		}

	}

	def main(args: Array[String]): Unit = {
		startConnector(0)
	}
}
