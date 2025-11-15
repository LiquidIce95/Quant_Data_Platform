package src.main.scala
import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.sys.process.ProcessLogger
import java.io.File
import scala.sys.process._
import sttp.client4.quick._

trait PortalManager {

    val portalOutput: StringBuilder = new StringBuilder


	protected val outputLock : AnyRef = new AnyRef
    // logger that appends all lines to portalOutput
	val portalLogger: ProcessLogger = ProcessLogger(
		(line: String) => {
			outputLock.synchronized {
			portalOutput.append(line).append('\n')
			}
		},
		(err: String) => {
			outputLock.synchronized {
			portalOutput.append(err).append('\n')
			}
		}
	)


	@volatile var portalProcFuture: Future[Unit] = Future.successful(())

    private def hasFatalErrorInLogs: Boolean = {
		outputLock.synchronized{
			val s = portalOutput.toString
			s.contains("Server listen failed") || s.contains("ERROR") || s.contains("Exception")
		}
	}


	/**
	  * computes the userId we need to use from the keyVault, based on this pods Id and nubmer of
	  * replicas in the cluster namespace
	  *
	  * @return
	  */
	def computeUser():Int

	/**
	  * starts the ib web api client for this pod with a bash command.
	  * cheecks the console output for "Open https://localhost:5000 to login"
	  *
	  * @return true if the string was present in the console output, false otherwise
	  * 
	  */
	def startIbPortal(): Boolean = {
		val workDir = new File("/work/services_streaming_lane/ib_connector/source/clientportal.gw")


		// kill all processes listening on port 5000
		val cmdKill = Seq(
			"bash",
			"-lc",
			"lsof -ti:5000 | xargs -r kill"
		).!

		Thread.sleep(2000L)
		val cmd = Seq(
			"bash",
			"-lc",
			"./bin/run.sh root/conf.yaml"
		)
		outputLock.synchronized{
			portalOutput.clear()
        	portalProcFuture = Future {
            	Process(cmd,workDir).!(portalLogger)
            	()
        	}

		}
		// start portal asynchronously; do not wait for termination

		// give the gateway a bit of time to print its startup banner
		Thread.sleep(3000L)

		outputLock.synchronized{
			portalOutput.toString.contains("Open https://localhost:5000 to login") &&
				!portalOutput.toString.contains("Server listen failed Address already in use")
		}

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
		Thread.sleep(10000L)
		output.toString.contains("\"authenticated\":true")
	}

    /**
     * checks if the client portal is running, if not we start it and authenticate again
     * this will be called by the startStream function inn StreamManager before ConnectionManager apply
     * @return a tuple where the first component is the result of startIbPortal and the second 
     * from authenticate
     * thorws an exception if its unable to start portal and authenticate in this case we need to restart pod
     */
    def apply():Unit={
        import scala.util.{Try, Success, Failure}

        // 1) Try to check current auth status via Web API
        val statusReq = ApiHandler.endpointsMap(EndPoints.AuthStatus)

        var portalRunning: Boolean = !portalProcFuture.isCompleted && !hasFatalErrorInLogs
		


        val isAuthOk: Boolean =
            if (portalRunning){
                Try {
                    val resp = statusReq.send()
                    if (resp.code.isSuccess) {
                        val json = ujson.read(resp.body)
                        json.obj.get("authenticated") match {
                            case Some(v) => v.bool
                            case None    => false
                        }
                    } else {
                        false
                    }
                } match {
                    case Success(b) => b
                    case Failure(_) => false
                }
            }
            else {
                false
            }
        // 2) If portal is reachable and authenticated, we’re done
        if (isAuthOk && portalRunning) {
            ()
        } else {
            val portalStarted = if (!portalRunning){
                startIbPortal()
            } 
            else {
                true
            }
            // 3) Otherwise: try to (re)start portal and re-authenticate
            val userId: Int             = computeUser()
            val authed: Boolean         = authenticate(userId)
        	portalRunning = !portalProcFuture.isCompleted && !hasFatalErrorInLogs
			

            if (portalStarted && authed && portalRunning) {
                ()
            } else {
                throw new Exception(s"we have a problem with the clinet portal, need to restart pod portalStarted: $portalStarted, authed:$authed, portalRunning:$portalRunning")
            }
        }
    }
    
}