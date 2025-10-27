// File: src/test/scala/TestConnManager.scala
package src.main.scala

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterEach

import com.ib.client._
import java.util

// Pure Java Mockito — no Scala sugar:
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.{eq => meq, _}
import org.mockito.verification.VerificationMode

final class TestConnManager extends AnyFunSuite with BeforeAndAfterEach {

	// ----- helpers -----

	private def newClient(): EClientSocket = mock(classOf[EClientSocket])

	private def newIo(c: EClientSocket): ClientIo =
		new ClientIo(c) // real implementation from prod

	private def seedDiscovery(reqId: Int, code: String): Unit = {
		Connections.putLookup(reqId, code)
		Connections.ensureEntry(code) // default: INVALID + DROPPED (both legs)
	}

	private def wireActors(mgr: ConnManager): Unit = {
		val ew = new EwrapperImplementation(null)
		Connections.setActors(ew, mgr)
	}

	private def startAndTick(mgr: ConnManager, pollMs: Long = 250L, extraWaitMs: Long = 200L): Unit = {
		mgr.start()
		mgr.startStreams(pollMs)
		Thread.sleep(math.max(300L, pollMs + extraWaitMs))
	}

	private def shutdown(mgr: ConnManager): Unit = {
		mgr.stop()
		Thread.sleep(50L)
	}

	override protected def beforeEach(): Unit = {
		Connections.reset()
	}

	// ======================================================================
	// Single-symbol tests (distributed=false => shard == universe)
	// ======================================================================

	test("both legs INVALID -> manager sets VALID and starts both legs (realtime)") {
		val client = newClient()
		val io = newIo(client)
		val reqId = 101
		val code	= "CLZ5"

		seedDiscovery(reqId, code)
		val mgr = new ConnManager(client, io, "realtime", distributed = false)
		wireActors(mgr)

		startAndTick(mgr)

		// Market data type requested
		verify(client, atLeastOnce()).reqMarketDataType(1)

		// Step (1): cancels for INVALID (safe even if not previously running)
		verify(client, atLeastOnce()).cancelTickByTickData(meq(reqId))
		verify(client, atLeastOnce()).cancelMktDepth(meq(reqId), meq(false))

		// Step (3): in-shard => (re)start both legs from INVALID -> VALID
		verify(client, atLeastOnce()).reqTickByTickData(
			meq(reqId),
			any(classOf[Contract]),
			meq("AllLast"),
			meq(0),
			meq(false)
		)
		verify(client, atLeastOnce()).reqMktDepth(
			meq(reqId),
			any(classOf[Contract]),
			meq(12),
			meq(false),
			any() // last arg (tags) — accept anything/null
		)

		// final states
		assert(Connections.stateOf(code, isL2 = false) == ConnState.VALID)
		assert(Connections.stateOf(code, isL2 = true)	== ConnState.VALID)
		assert(Connections.statusOf(code, isL2 = false) == Connections.ON)
		assert(Connections.statusOf(code, isL2 = true)	== Connections.ON)

		shutdown(mgr)
	}

	test("only TBT INVALID (L2 already VALID) -> start TBT only") {
		val client = newClient()
		val io = newIo(client)
		val reqId = 201
		val code	= "NGZ5"

		seedDiscovery(reqId, code)
		val mgr = new ConnManager(client, io, "realtime", distributed = false)
		wireActors(mgr)

		// Precondition: L2 VALID, TBT INVALID
		Connections.setState(mgr, code, isL2 = true, ConnState.VALID)

		startAndTick(mgr)

		// TBT must (re)start
		verify(client, atLeastOnce()).reqTickByTickData(
			meq(reqId),
			any(classOf[Contract]),
			meq("AllLast"),
			meq(0),
			meq(false)
		)
		// L2 must NOT (re)start
		verify(client, never()).reqMktDepth(
			meq(reqId),
			any(classOf[Contract]),
			anyInt(),
			anyBoolean(),
			any()
		)

		// final states
		assert(Connections.stateOf(code, isL2 = false) == ConnState.VALID)
		assert(Connections.stateOf(code, isL2 = true)	== ConnState.VALID)
		assert(Connections.statusOf(code, isL2 = false) == Connections.ON)
		assert(Connections.statusOf(code, isL2 = true)	== Connections.ON)

		shutdown(mgr)
	}

	test("only L2 INVALID (TBT already VALID) -> start L2 only (realtime)") {
		val client = newClient()
		val io = newIo(client)
		val reqId = 301
		val code	= "CLF6"

		seedDiscovery(reqId, code)
		val mgr = new ConnManager(client, io, "realtime", distributed = false)
		wireActors(mgr)

		// Precondition: TBT VALID, L2 INVALID
		Connections.setState(mgr, code, isL2 = false, ConnState.VALID)

		startAndTick(mgr)

		// L2 must (re)start
		verify(client, atLeastOnce()).reqMktDepth(
			meq(reqId),
			any(classOf[Contract]),
			meq(12),
			meq(false),
			any()
		)
		// TBT must NOT (re)start
		verify(client, never()).reqTickByTickData(
			meq(reqId),
			any(classOf[Contract]),
			anyString(),
			anyInt(),
			anyBoolean()
		)

		// final states
		assert(Connections.stateOf(code, isL2 = false) == ConnState.VALID)
		assert(Connections.stateOf(code, isL2 = true)	== ConnState.VALID)
		assert(Connections.statusOf(code, isL2 = false) == Connections.ON)
		assert(Connections.statusOf(code, isL2 = true)	== Connections.ON)

		shutdown(mgr)
	}

	test("VALID & ON for both legs (in-shard) -> no restarts, no cancels") {
		val client = newClient()
		val io = newIo(client)
		val reqId = 401
		val code	= "NGF6"

		seedDiscovery(reqId, code)
		val mgr = new ConnManager(client, io, "realtime", distributed = false)
		wireActors(mgr)

		// Precondition: both legs VALID & ON
		Connections.setState(mgr, code, isL2 = false, ConnState.VALID)
		Connections.setState(mgr, code, isL2 = true,	ConnState.VALID)
		Connections.setStatus(mgr, code, isL2 = false, Connections.ON)
		Connections.setStatus(mgr, code, isL2 = true,	Connections.ON)

		startAndTick(mgr)

		// benign md type call allowed
		verify(client, atLeastOnce()).reqMarketDataType(1)

		// No cancels or restarts
		verify(client, never()).cancelTickByTickData(meq(reqId))
		verify(client, never()).cancelMktDepth(meq(reqId), anyBoolean())
		verify(client, never()).reqTickByTickData(meq(reqId), any(classOf[Contract]), anyString(), anyInt(), anyBoolean())
		verify(client, never()).reqMktDepth(meq(reqId), any(classOf[Contract]), anyInt(), anyBoolean(), any())

		// states unchanged
		assert(Connections.stateOf(code, isL2 = false) == ConnState.VALID)
		assert(Connections.stateOf(code, isL2 = true)	== ConnState.VALID)
		assert(Connections.statusOf(code, isL2 = false) == Connections.ON)
		assert(Connections.statusOf(code, isL2 = true)	== Connections.ON)

		shutdown(mgr)
	}
}
