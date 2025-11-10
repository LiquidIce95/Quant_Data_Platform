package src.main.scala

import org.scalatest.funsuite.AnyFunSuite

class TestConnectionManager extends AnyFunSuite {

  // This test assumes that `az login` has already been performed successfully
  // inside the devcontainer, so that the Python authenticator can read secrets
  // from Azure Key Vault via DefaultAzureCredential.

  test("start ib portal and authenticate") {
    // trivial implementation for testing: computeShards returns empty map
    val cm = new ConnectionManager {
      def computeShards(): Map[String, List[String]] = Map.empty
    }

    val portalStarted: Boolean   = cm.startIbPortal()
    val authenticated: Boolean   = cm.authenticate(1)

    assert(portalStarted)
    assert(authenticated)
  }
}
