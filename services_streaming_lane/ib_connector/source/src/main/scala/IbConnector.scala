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
import src.main.scala.KubernetesApiHandler.getPodName
import io.fabric8.kubernetes.api.model.Pod

object IbConnector {
	
	@tailrec
	def startConnector(retries:Int=0){
		println(f"startConnector called, retries :${retries}")
		if (retries > 5) {
			throw new Exception("We tried to start the connector more than 5 times...")
		}
		Try {				
			val api :ApiHandler = new ApiHandler {
				def computeUser(): Int = {
                    val peers = KubernetesApiHandler.getPeers()

                    val peerNames = {for (peer<-peers) yield getPodName(peer)}.toVector

                    val userNumber:Int = peerNames.indexOf(KubernetesApiHandler.getThisPodName())
                    userNumber
                }
			}

			api.startApi()
			println(f"api started ${api.isHealthy()}")

            val producer = new KafkaProducerApi(api=api)

            val smdProcessor = new SmdProcessor(producer,api) 
            val peers = KubernetesApiHandler.getPeers()
            val symbolUniverse = api.computeSymbolUniverse()
            val peerNames = {for (peer<-peers) yield getPodName(peer)}.toVector.sortBy(x=>x)

            val roundRobin = new RoundRobin[String,(Long,String,String)](peerNames,symbolUniverse)

			object CM extends ConnectionManager(api) {
				def computeShards(): mutable.Map[String, Vector[(Long, String,String)]] = {
					val onlinePeers = KubernetesApiHandler.getHealthyPeers()

                    val onlinePeersNames = {for (peer<-onlinePeers) yield KubernetesApiHandler.getPodName(peer)}.toVector

                    roundRobin(onlinePeersNames)
					
				}
				def determinePodIdentity(): String = KubernetesApiHandler.getThisPodName()
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
						webSocketOpt.get
					}
					CM(ws)

				}
				def onSmdFrame(message: LinkedHashMap[String, ujson.Value]): Unit = {
					smdProcessor(message)
				}
				def onSbdFrame(message: LinkedHashMap[String, ujson.Value]): Unit = {
					()
				}
			}

			// make sure you ran az login on this container and the managed identiy is set up in production
			SM.startStream()

			// keep the test alive
		} match {
			case scala.util.Success(_) =>
                // this is a good palce to create a log 
                println("the connector started successfully")
				() 
			case scala.util.Failure(e) =>
				println(s"startConnector failed: ${e.getMessage}")
				startConnector(retries + 1)
		}

	}

	def main(args: Array[String]): Unit = {
		startConnector(6)
	}
}
