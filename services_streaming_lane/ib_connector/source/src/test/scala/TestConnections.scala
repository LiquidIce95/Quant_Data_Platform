// File: src/test/scala/TestConnections.scala
package src.main.scala

import org.scalatest.funsuite.AnyFunSuite
import com.ib.client._
import java.util.concurrent.CountDownLatch
import scala.collection.mutable

final class TestConnections extends AnyFunSuite {

	private def newEw(): EwrapperImplementation = new EwrapperImplementation(null)
	private def newCm(): ConnManager = new ConnManager(null, null, "delayed")

	private def initWithActors(): (EwrapperImplementation, ConnManager) = {
		Connections.reset()
		val ew = newEw()
		val cm = newCm()
		Connections.setActors(ew, cm)
		(ew, cm)
	}

	private def ensureCode(code: String): Unit = {
		Connections.ensureEntry(code)
	}

	private def st(code: String, isL2: Boolean): ConnState.State =
		Connections.stateOf(code, isL2).get

	private def ss(code: String, isL2: Boolean): Connections.ConnStatus.Value =
		Connections.statusOf(code, isL2).get

	// 1) reset clears everything
	test("reset clears state/lookup/status and actors") {
		Connections.reset()
		assert(Connections.discoveryEmpty == true)
	}

	// 2) setActors enables guarded mutations
	test("setActors registers permitted instances") {
		val (ew, cm) = initWithActors()
		ensureCode("SYM")
		Connections.setState(cm, "SYM", false, ConnState.VALID)
		assert(st("SYM", false) == ConnState.VALID)
	}

	// 3) ensureEntry creates INIT + DROPPED for both legs
	test("ensureEntry initializes INIT/DROPPED") {
		initWithActors()
		ensureCode("SYM")
		assert(st("SYM", false) == ConnState.INIT)
		assert(st("SYM", true)  == ConnState.INIT)
		assert(ss("SYM", false) == Connections.ConnStatus.DROPPED)
		assert(ss("SYM", true)  == Connections.ConnStatus.DROPPED)
	}

	// 4) putLookup + entriesSortedByReqId ordering
	test("entriesSortedByReqId returns ascending reqIds") {
		initWithActors()
		Connections.putLookup(10, "B")
		Connections.putLookup(2, "A")
		Connections.putLookup(7, "C")
		val xs = Connections.entriesSortedByReqId
		assert(xs.map(_._1) == Seq(2,7,10))
	}

	// 5) lookupFor unknown returns '?'
	test("lookupFor unknown reqId returns '?'") {
		initWithActors()
		assert(Connections.lookupFor(9999) == "?")
	}

	// 6) manager INIT → VALID allowed (tbt)
	test("manager INIT→VALID allowed (tbt)") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setState(cm, "X", false, ConnState.VALID)
		assert(st("X", false) == ConnState.VALID)
	}

	// 7) ewrapper INIT → VALID ignored
	test("ewrapper INIT→VALID ignored") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setState(ew, "X", false, ConnState.VALID)
		assert(st("X", false) == ConnState.INIT)
	}

	// 8) manager VALID → INVALID allowed
	test("manager VALID→INVALID allowed") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setState(cm, "X", false, ConnState.VALID)
		Connections.setState(cm, "X", false, ConnState.INVALID)
		assert(st("X", false) == ConnState.INVALID)
	}

	// 9) ewrapper VALID → INVALID allowed
	test("ewrapper VALID→INVALID allowed") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setState(cm, "X", true, ConnState.VALID)
		Connections.setState(ew, "X", true, ConnState.INVALID)
		assert(st("X", true) == ConnState.INVALID)
	}

	// 10) manager INVALID → VALID disallowed (stays INVALID)
	test("manager INVALID→VALID disallowed") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setState(cm, "X", false, ConnState.VALID)
		Connections.setState(cm, "X", false, ConnState.INVALID)
		Connections.setState(cm, "X", false, ConnState.VALID)
		assert(st("X", false) == ConnState.INVALID)
	}

	// 11) manager INIT → INVALID allowed
	test("manager INIT→INVALID allowed") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setState(cm, "X", false, ConnState.INVALID)
		assert(st("X", false) == ConnState.INVALID)
	}

	// 12) ewrapper INIT → INVALID allowed
	test("ewrapper INIT→INVALID allowed") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setState(ew, "X", true, ConnState.INVALID)
		assert(st("X", true) == ConnState.INVALID)
	}

	// 13) status only manager can change
	test("status set by ewrapper is ignored") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setStatus(ew, "X", false, Connections.ConnStatus.ON)
		assert(ss("X", false) == Connections.ConnStatus.DROPPED)
	}

	// 14) manager can set status ON ⇄ DROPPED
	test("manager can flip status ON/DROPPED") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setStatus(cm, "X", false, Connections.ConnStatus.ON)
		assert(ss("X", false) == Connections.ConnStatus.ON)
		Connections.setStatus(cm, "X", false, Connections.ConnStatus.DROPPED)
		assert(ss("X", false) == Connections.ConnStatus.DROPPED)
	}

	// 15) setState unknown code is ignored
	test("setState on unknown code is ignored (no throw)") {
		val (ew, cm) = initWithActors()
		Connections.setState(cm, "NOPE", false, ConnState.VALID)
		// cannot read unknown, but no exception means pass
		assert(true)
	}

	// 16) setStatus unknown code is ignored
	test("setStatus on unknown code is ignored (no throw)") {
		val (ew, cm) = initWithActors()
		Connections.setStatus(cm, "NOPE", false, Connections.ConnStatus.ON)
		assert(true)
	}

	// 17) manager VALID on L2 while TBT INIT remains INIT
	test("manager VALID on L2 only") {
		val (ew, cm) = initWithActors()
		ensureCode("S")
		Connections.setState(cm, "S", true, ConnState.VALID)
		assert(st("S", true)  == ConnState.VALID)
		assert(st("S", false) == ConnState.INIT)
	}

	// 18) ewrapper VALID→INVALID on L2 path
	test("ewrapper invalidate L2") {
		val (ew, cm) = initWithActors()
		ensureCode("S")
		Connections.setState(cm, "S", true, ConnState.VALID)
		Connections.setState(ew, "S", true, ConnState.INVALID)
		assert(st("S", true) == ConnState.INVALID)
	}

	// 19) entriesSortedByReqId stable for multiple codes
	test("entriesSortedByReqId stable mapping") {
		initWithActors()
		Connections.putLookup(5, "X")
		Connections.putLookup(1, "A")
		Connections.putLookup(3, "B")
		val xs = Connections.entriesSortedByReqId
		assert(xs.map(_._2) == Seq("A","B","X"))
	}

	// 20) ensureEntry idempotent
	test("ensureEntry idempotent") {
		initWithActors()
		ensureCode("X")
		Connections.ensureEntry("X")
		assert(st("X", false) == ConnState.INIT)
		assert(st("X", true)  == ConnState.INIT)
	}

	// 21) after reset, mutations with old actors are ignored
	test("mutations after reset are ignored until new actors set") {
		val ew = newEw()
		val cm = newCm()
		Connections.reset()
		ensureCode("X")
		Connections.setState(cm, "X", false, ConnState.VALID)  // cm not registered
		assert(st("X", false) == ConnState.INIT)
	}

	// 22) discoveryEmpty true when either map empty
	test("discoveryEmpty reflects lookup/state emptiness") {
		initWithActors()
		assert(Connections.discoveryEmpty == true)
		ensureCode("X")
		assert(Connections.discoveryEmpty == true) // lookup still empty
		Connections.putLookup(5001, "X")
		assert(Connections.discoveryEmpty == false)
	}

	// 23) idempotent VALID
	test("idempotent VALID set") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setState(cm, "X", false, ConnState.VALID)
		Connections.setState(cm, "X", false, ConnState.VALID)
		assert(st("X", false) == ConnState.VALID)
	}

	// 24) manager cannot set INIT from anything
	test("INIT cannot be set as target") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setState(cm, "X", false, ConnState.VALID)
		Connections.setState(cm, "X", false, ConnState.INVALID)
		Connections.setState(cm, "X", false, ConnState.INIT)
		assert(st("X", false) == ConnState.INVALID)
	}

	// 25) manager can flip status without touching state
	test("status flips do not change state") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		assert(st("X", false) == ConnState.INIT)
		Connections.setStatus(cm, "X", false, Connections.ConnStatus.ON)
		Connections.setStatus(cm, "X", false, Connections.ConnStatus.DROPPED)
		assert(st("X", false) == ConnState.INIT)
	}

	// 26) ewrapper cannot change status
	test("ewrapper cannot change status (ON)") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setStatus(ew, "X", true, Connections.ConnStatus.ON)
		assert(ss("X", true) == Connections.ConnStatus.DROPPED)
	}

	// 27) concurrent ew invalid vs manager valid ends INVALID from VALID
	test("concurrent ew INVALID vs manager VALID resolves to INVALID") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setState(cm, "X", false, ConnState.VALID)

		val start = new CountDownLatch(1)
		val done  = new CountDownLatch(2)

		val th1 = new Thread(() => { start.await(); Connections.setState(cm, "X", false, ConnState.VALID); done.countDown() })
		val th2 = new Thread(() => { start.await(); Connections.setState(ew, "X", false, ConnState.INVALID); done.countDown() })

		th1.start(); th2.start()
		start.countDown(); done.await()

		assert(st("X", false) == ConnState.INVALID)
	}

	// 28) concurrent many ew invalids ends INVALID
	test("multiple ew INVALID calls converge to INVALID") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setState(cm, "X", true, ConnState.VALID)

		val start = new CountDownLatch(1)
		val done  = new CountDownLatch(5)
		val threads = (1 to 5).map { _ =>
			new Thread(() => { start.await(); Connections.setState(ew, "X", true, ConnState.INVALID); done.countDown() })
		}
		threads.foreach(_.start())
		start.countDown(); done.await()

		assert(st("X", true) == ConnState.INVALID)
	}

	// 29) unauthorized caller (random object) cannot mutate
	test("unauthorized caller ignored") {
		initWithActors()
		ensureCode("X")
		val rnd = new Object()
		Connections.setState(rnd, "X", false, ConnState.VALID)
		Connections.setStatus(rnd, "X", false, Connections.ConnStatus.ON)
		assert(st("X", false) == ConnState.INIT)
		assert(ss("X", false) == Connections.ConnStatus.DROPPED)
	}

	// 30) manager VALID on both legs + ew INVALID on one leg affects only that leg
	test("leg-specific transitions do not leak") {
		val (ew, cm) = initWithActors()
		ensureCode("X")
		Connections.setState(cm, "X", false, ConnState.VALID)
		Connections.setState(cm, "X", true,  ConnState.VALID)
		Connections.setState(ew, "X", true,  ConnState.INVALID)

		assert(st("X", false) == ConnState.VALID)
		assert(st("X", true)  == ConnState.INVALID)
	}
}
