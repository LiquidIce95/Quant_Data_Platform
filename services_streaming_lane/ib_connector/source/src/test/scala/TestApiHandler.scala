package src.main.scala

import org.scalatest.funsuite.AnyFunSuite
import scala.annotation.tailrec
import scala.util.Try


class TestApiHandler extends AnyFunSuite {

    def createApiImplementation():ApiHandler={
        val api = new ApiHandler{
            def computeUser(): Int = 1

        }
        api
    }

    /**
      * this test needs you to run az login on the container to work!
      * the python authenticator script needs it
      */
    test("The portal should start at least once in three tries") {
        val api = createApiImplementation()

        @tailrec
        def tryToStartPortal(trial:Int):Boolean={
            if (trial==0) {
                false
            }
            else if (api.startIbPortal()){
                true
            } else {
                tryToStartPortal(trial-1)
            }
        }
        assert(tryToStartPortal(3))
    }

    /**
      * this test needs you to run az login on the container to work!
      * the python authenticator script needs it
    */
    test("when startApi terminates, portal needs to be healty or it must throw an exception"){
        val api = createApiImplementation()
        val started = Try(api.startApi())
        assert(started.isFailure || api.isHealthy())
    }



}