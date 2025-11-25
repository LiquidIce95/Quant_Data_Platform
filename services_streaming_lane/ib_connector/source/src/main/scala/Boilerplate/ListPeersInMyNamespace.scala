package Boilerplate

import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientBuilder}
import scala.io.Source

object ListPeersInMyNamespace {

	def main(args: Array[String]): Unit = {
		// 1. Read current namespace from service account file
		val nsPath = "/var/run/secrets/kubernetes.io/serviceaccount/namespace"
		val currentNamespace = Source.fromFile(nsPath).getLines().mkString.trim

		// 2. In-cluster config is picked up automatically by KubernetesClientBuilder
		val client: KubernetesClient = new KubernetesClientBuilder().build()

		try {
			val pods = client
				.pods()
				.inNamespace(currentNamespace)
				.list()
				.getItems

			val podNames = pods.toArray.map(_.asInstanceOf[io.fabric8.kubernetes.api.model.Pod].getMetadata.getName)
			podNames.foreach(println)
		} finally {
			client.close()
		}
	}
}

