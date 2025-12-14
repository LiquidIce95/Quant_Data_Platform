package src.main.scala

import ujson._
import upickle.core.LinkedHashMap
import scala.collection.mutable
import scala.util.Try
import scala.annotation.tailrec

/**
  * Make sure to run 'az login' before running this
  */
object ExampleUse {

	@tailrec
	def startConnector(retries:Int=0): Unit = {
		println(f"startConnector called, retries :${retries}")
		if (retries > 5) {
			throw new Exception("We tried to start the connector more than 5 times...")
		}

		Try {
			val api: ApiHandler = new ApiHandler {
				def computeUser(): Int = 1
	
			}

			api.startApi()
			println(f"api started ${api.isHealthy()}")

			val symbolUniverse: Vector[(Long, String, String)] =
				api.computeSymbolUniverse()

			assert(symbolUniverse.nonEmpty)

			val pick: Vector[(Long, String, String)] =
				symbolUniverse.take(1)

			println(s"DEBUG pick = ${pick}")

			object CM extends ConnectionManager(api) {
				def computeShards(): mutable.Map[String, Vector[(Long, String, String)]] =
					mutable.Map("one" -> pick)

				def determinePodIdentity(): String =
					"one"
			}

			object SM extends StreamManager(api) {

				def connectionsLifeCycleManagement(): Unit = {
					val ws = webSocketLock.synchronized { webSocket }
					CM.apply(ws)
				}

				def onSmdFrame(message: LinkedHashMap[String, ujson.Value]): Unit = {
					println(ujson.write(ujson.Obj.from(message)))
				}

				def onSbdFrame(message: LinkedHashMap[String, ujson.Value]): Unit = {
					println(ujson.write(ujson.Obj.from(message)))
				}
			}

			SM.startStream()
		} match {
			case scala.util.Success(_) => ()
			case scala.util.Failure(e) =>
				println(s"startConnector failed: ${e.getMessage}")
				startConnector(retries + 1)
		}
	}

	def main(args: Array[String]): Unit = {
		startConnector(0)
	}
}
