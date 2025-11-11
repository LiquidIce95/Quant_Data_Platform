package src.main.scala

import scala.sys.process._
import java.io.File

trait ConnectionManager {

	var symbolShards: Map[String, List[String]] = Map.empty

	val symbolUniverse: List[String] = Nil

	// collects stdout/stderr from the portal process
	val portalOutput: StringBuilder = new StringBuilder

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
	  * for the current symbols that are managed by this pod, check L2 and tbt connection if they are healthy
	  * if not , restart the feed
	  */
	def restartUnhealthyConnections(): Unit = {

	}

	/**
	  * starts the lifecycle management of the connections, checks if any connections silently died, discovers peers
	  * over the kubernetes api, uses the provided symbolsharding function to compute the new responsabilities per pod
	  * @param timeIntervall milliseconds, after what time should we do the next lifecycle iteration and perform
	  */
	def startLifeCycleManagement(timeIntervall: Int): Unit = {

	}

}
