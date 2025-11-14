package src.main.scala

import org.scalatest.funsuite.AnyFunSuite
import ujson.Value
import upickle.core.LinkedHashMap

import sttp.client4.quick._
import sttp.client4.Response
import sttp.client4.quick
import sttp.model.StatusCodes
import sttp.model.StatusCode

class TestPortalManager extends AnyFunSuite {

  // This test assumes that `az login` has already been performed successfully
  // inside the devcontainer, so that the Python authenticator can read secrets
  // from Azure Key Vault via DefaultAzureCredential.

  test("start ib portal and authenticate") {
    // trivial implementation for testing: computeShards returns empty map
    object PM extends PortalManager {
			def computeUser(): Int = 1
		}

    PM.apply()
    
    assert(!PM.portalProcFuture.isCompleted)
    assert(PM.portalOutput.toString.contains("Open https://localhost:5000 to login"))
    val response: Response[String] = quickRequest
			.get(uri"https://localhost:5000/v1/api/iserver/auth/status")
			.send()

    assert(response.code==StatusCode.Ok)
    assert(response.body.contains("\"authenticated\":true"))
  }
}
