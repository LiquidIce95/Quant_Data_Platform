package src.main.scala


import ujson._
import sttp.client4.quick._
import sttp.ws.WebSocketFrame
import java.nio.charset.StandardCharsets
import sttp.client4.ws.SyncWebSocket
import upickle.core.LinkedHashMap
import scala.sys.process._
import scala.collection.mutable

object IntegrationTestLight {

	def main(args: Array[String]): Unit = {

		val api :ApiHandler = new ApiHandler {
			def computeUser(): Int = {1}
		}

		api.startApi()

		println(f"api started, ${api.isHealthy()}")

		object CM extends ConnectionManager(api) {
			def computeShards(): mutable.Map[String, Vector[(Long, String, String)]] = {
				// pick the 2nd CL from symbolUniverse (which was built as CL-front5 ++ NG-front5)
				val pick: Vector[(Long, String, String)] = symbolUniverse.take(2)
				mutable.Map("one" -> pick)
			}
			def determinePodIdentity(): String = "one"
			
		}

		val producer = new KafkaProducerApi(api=api)

		val smdProcessor = new SmdProcessor(producer,api)

		object SM extends StreamManager(api) {

			def connectionsLifeCycleManagement(): Unit = {
				val ws = webSocketLock.synchronized {
					webSocket
				}
				CM.apply(ws)

			}
			def onSmdFrame(message: LinkedHashMap[String, ujson.Value]): Unit = {
				smdProcessor.apply(message)
			}
			def onSbdFrame(message: LinkedHashMap[String, ujson.Value]): Unit = {
				println(ujson.write(ujson.Obj.from(message)))
			}
		}

		// make sure you ran az login on this container
		SM.startStream()

		// keep the test alive
		Thread.sleep(Long.MaxValue)
	}
}
