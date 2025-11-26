
package  src.main.scala
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientBuilder}
import scala.io.Source
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.PodStatus

object KubernetesApiHandler {
	val myPodName: String      = sys.env.getOrElse("POD_NAME","unknown")
	val myPodNamespace: String = sys.env.getOrElse("POD_NAMESPACE","unknown")


	def getPeers(): Vector[Pod] = {
		// 1. Read current namespace from service account file
		val nsPath = "/var/run/secrets/kubernetes.io/serviceaccount/namespace"
		val currentNamespace = Source.fromFile(nsPath).getLines().mkString.trim

		// 2. In-cluster config is picked up automatically by KubernetesClientBuilder
		val client: KubernetesClient = new KubernetesClientBuilder().build()


		val pods : java.util.List[Pod] = client.pods().inNamespace(currentNamespace).list().getItems

		var results : Vector[Pod] = Vector.empty[Pod]

		for (i<-0 until pods.size()){
			val pod = pods.get(i)

			results = results ++ Vector(pod)
		}

		results.sortBy(x=>x.getMetadata().getName())
	}

	def getHealthyPeers():Vector[Pod] ={
		val peers = getPeers()

		val healthyPeers = {for (peer<-peers if peer.getStatus().getPhase()=="Running") yield peer}.toVector
		healthyPeers
	}

	def getOfflinePeers():Vector[Pod]={
		val peers = getPeers()
		val offlinePeers = {for (peer<-peers if peer.getStatus().getPhase()=="Failed") yield peer}.toVector
		offlinePeers
	}

	def getPodName(pod:Pod):String ={
		pod.getMetadata().getName()
	}

	def getThisPodName() : String ={
		myPodName
	}

	def getThisNameSpace():String ={
		myPodNamespace
	}
}

