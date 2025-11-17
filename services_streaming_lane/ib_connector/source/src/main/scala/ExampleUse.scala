package src.main.scala

import ujson._
import sttp.client4.quick._
import sttp.ws.WebSocketFrame
import java.nio.charset.StandardCharsets
import sttp.client4.ws.SyncWebSocket
import upickle.core.LinkedHashMap
import scala.sys.process._

object ExampleUse {
	def main(args: Array[String]): Unit = {

		object PM extends PortalManager {
			def computeUser(): Int = 1
		}

		object CM extends ConnectionManager {
			def computeShards(): Map[String, List[(Long, String)]] = {
				// pick the 2nd CL from symbolUniverse (which was built as CL-front5 ++ NG-front5)
				val pick: List[(Long, String)] = symbolUniverse.lift(1).toList
				Map("one" -> pick)
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

		object SM extends StreamManager {

			def connectionsLifeCycleManagement(): Unit = {
				val ws = webSocketLock.synchronized {
					webSocketOpt.get
				}
				CM.apply(ws)

			}
			def onSmdFrame(message: LinkedHashMap[String, ujson.Value]): Unit = {
				println(ujson.write(ujson.Obj.from(message)))
			}
			def onSbdFrame(message: LinkedHashMap[String, ujson.Value]): Unit = {
				println(ujson.write(ujson.Obj.from(message)))
			}
			def portalLifeCycleManagement(): Unit = PM.apply()
		}

		// make sure you ran az login on this container
		SM.startStream()

		// keep the test alive
		Thread.sleep(Long.MaxValue)
	}
}
