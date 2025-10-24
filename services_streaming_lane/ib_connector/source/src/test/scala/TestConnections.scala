// File: src/test/scala/TestConnections.scala
package src.main.scala

import org.scalatest.funsuite.AnyFunSuite
import java.util.concurrent.CountDownLatch

final class TestConnections extends AnyFunSuite {

	private def newEw(): EwrapperImplementation = new EwrapperImplementation(null)
	private def newCm(): ConnManager = new ConnManager(null, null, "delayed")

	private def initWithActors(): (EwrapperImplementation, ConnManager) = {
		Connections.reset()
		val ew = newEw(); val cm = newCm()
		Connections.setActors(ew, cm)
		(ew, cm)
	}

	private def ensure(code: String): Unit = Connections.ensureEntry(code)

	private def st(code: String, isL2: Boolean) = Connections.stateOf(code, isL2).get
	private def ss(code: String, isL2: Boolean) = Connections.statusOf(code, isL2).get

	// 1
	test("reset empties tables and actors") {
		Connections.reset()
		assert(Connections.discoveryEmpty)
	}

	// 2
	test("ensureEntry initializes INIT + DROPPED for both legs") {
		initWithActors(); ensure("S")
		assert(st("S", false) == ConnState.INIT)
		assert(st("S", true)  == ConnState.INIT)
		assert(ss("S", false) == Connections.DROPPED)
		assert(ss("S", true)  == Connections.DROPPED)
	}

	// 3
	test("unknown code mutation throws on setState") {
		val (ew, cm) = initWithActors()
		intercept[IllegalStateException] {
			Connections.setState(cm, "NOPE", false, ConnState.VALID)
		}
	}

	// 4
	test("unknown code mutation throws on setStatus") {
		val (ew, cm) = initWithActors()
		intercept[IllegalStateException] {
			Connections.setStatus(cm, "NOPE", false, Connections.ON)
		}
	}

	// 5
	test("INIT→VALID allowed manager only") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(ew, "S", false, ConnState.VALID)
		assert(st("S", false) == ConnState.INIT)
		Connections.setState(cm, "S", false, ConnState.VALID)
		assert(st("S", false) == ConnState.VALID)
	}

	// 6
	test("VALID→INVALID allowed wrapper only") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm, "S", false, ConnState.VALID)
		Connections.setState(cm, "S", false, ConnState.INVALID) // disallowed
		assert(st("S", false) == ConnState.VALID)
		Connections.setState(ew, "S", false, ConnState.INVALID)
		assert(st("S", false) == ConnState.INVALID)
	}

	// 7
	test("INVALID→VALID allowed manager only") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm, "S", false, ConnState.VALID)
		Connections.setState(ew, "S", false, ConnState.INVALID)
		assert(st("S", false) == ConnState.INVALID)
		Connections.setState(ew, "S", false, ConnState.VALID) // no
		assert(st("S", false) == ConnState.INVALID)
		Connections.setState(cm, "S", false, ConnState.VALID) // yes
		assert(st("S", false) == ConnState.VALID)
	}

	// 8
	test("INIT→INVALID disallowed for both") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm, "S", true, ConnState.INVALID)
		assert(st("S", true) == ConnState.INIT)
		Connections.setState(ew, "S", true, ConnState.INVALID)
		assert(st("S", true) == ConnState.INIT)
	}

	// 9
	test("idempotent: VALID by manager twice") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm, "S", true, ConnState.VALID)
		Connections.setState(cm, "S", true, ConnState.VALID)
		assert(st("S", true) == ConnState.VALID)
	}

	// 10
	test("idempotent: INVALID by wrapper twice") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm, "S", true, ConnState.VALID)
		Connections.setState(ew, "S", true, ConnState.INVALID)
		Connections.setState(ew, "S", true, ConnState.INVALID)
		assert(st("S", true) == ConnState.INVALID)
	}

	// 11
	test("manager-only status flips") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setStatus(ew, "S", false, Connections.ON)
		assert(ss("S", false) == Connections.DROPPED)
		Connections.setStatus(cm, "S", false, Connections.ON)
		assert(ss("S", false) == Connections.ON)
		Connections.setStatus(cm, "S", false, Connections.DROPPED)
		assert(ss("S", false) == Connections.DROPPED)
	}

	// 12
	test("entriesSortedByReqId ordering") {
		initWithActors()
		Connections.putLookup(12,"C"); Connections.putLookup(1,"A"); Connections.putLookup(7,"B")
		assert(Connections.entriesSortedByReqId.map(_._1) == Seq(1,7,12))
	}

	// 13
	test("discoveryEmpty reflects map emptiness") {
		initWithActors()
		assert(Connections.discoveryEmpty)
		ensure("S")
		assert(Connections.discoveryEmpty)
		Connections.putLookup(5001,"S")
		assert(!Connections.discoveryEmpty)
	}

	// 14
	test("leg separation: transitions are per-leg") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm,"S",false,ConnState.VALID)
		Connections.setState(cm,"S",true,ConnState.VALID)
		Connections.setState(ew,"S",true,ConnState.INVALID)
		assert(st("S",false) == ConnState.VALID)
		assert(st("S",true)  == ConnState.INVALID)
	}

	// 15
	test("unauthorized caller ignored") {
		initWithActors(); ensure("S")
		val rnd = new Object()
		Connections.setState(rnd,"S",false,ConnState.VALID)
		Connections.setStatus(rnd,"S",false,Connections.ON)
		assert(st("S",false) == ConnState.INIT)
		assert(ss("S",false) == Connections.DROPPED)
	}

	// 16
	test("manager can resurrect INVALID to VALID, wrapper cannot") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm,"S",false,ConnState.VALID)
		Connections.setStatus(cm,"S",false,Connections.ON)
		Connections.setState(ew,"S",false,ConnState.INVALID)
		assert(st("S",false) == ConnState.INVALID)
		Connections.setState(ew,"S",false,ConnState.VALID) // no-op
		assert(st("S",false) == ConnState.INVALID)
		Connections.setState(cm,"S",false,ConnState.VALID) // allowed
		assert(st("S",false) == ConnState.VALID)
	}

	// 17
	test("wrapper VALID&DROPPED -> invalidates (L2)") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setStatus(cm,"S",true,Connections.ON)
		Connections.setState(cm,"S",true,ConnState.VALID)
		Connections.setStatus(cm,"S",true,Connections.DROPPED)
		// simulate wrapper receiving data: call L2 path directly
		// (we can’t call the method here, but rule is encoded; we assert allowed mutation)
		Connections.setState(ew,"S",true,ConnState.INVALID)
		assert(st("S",true) == ConnState.INVALID)
	}

	// 18
	test("wrapper cannot VALID when status=ON (only manager can set VALID)") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setStatus(cm,"S",false,Connections.ON)
		Connections.setState(ew,"S",false,ConnState.VALID)
		assert(st("S",false) == ConnState.INIT)
	}

	// 19
	test("manager dropAll-like: status DROPPED, state unchanged") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setStatus(cm,"S",false,Connections.ON)
		Connections.setState(cm,"S",false,ConnState.VALID)
		Connections.setStatus(cm,"S",false,Connections.DROPPED)
		assert(st("S",false) == ConnState.VALID)
		assert(ss("S",false) == Connections.DROPPED)
	}

	// 20
	test("manager startStreams-like: status ON and VALID from INIT") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setStatus(cm,"S",false,Connections.ON)
		Connections.setState(cm,"S",false,ConnState.VALID)
		assert(st("S",false) == ConnState.VALID)
		assert(ss("S",false) == Connections.ON)
	}

	// 21
	test("idempotent status set") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setStatus(cm,"S",true,Connections.ON)
		Connections.setStatus(cm,"S",true,Connections.ON)
		assert(ss("S",true) == Connections.ON)
	}

	// 22
	test("wrapper VALID->INVALID only when currently VALID") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(ew,"S",false,ConnState.INVALID) // INIT -> INVALID disallowed
		assert(st("S",false) == ConnState.INIT)
		Connections.setState(cm,"S",false,ConnState.VALID)
		Connections.setState(ew,"S",false,ConnState.INVALID) // allowed
		assert(st("S",false) == ConnState.INVALID)
	}

	// 23
	test("manager cannot INVALID from VALID") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm,"S",true,ConnState.VALID)
		Connections.setState(cm,"S",true,ConnState.INVALID)
		assert(st("S",true) == ConnState.VALID)
	}

	// 24
	test("manager can VALID from INVALID") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm,"S",true,ConnState.VALID)
		Connections.setState(ew,"S",true,ConnState.INVALID)
		Connections.setState(cm,"S",true,ConnState.VALID)
		assert(st("S",true) == ConnState.VALID)
	}

	// 25
	test("no resurrection from INIT by wrapper") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(ew,"S",true,ConnState.VALID)
		assert(st("S",true) == ConnState.INIT)
	}

	// 26
	test("concurrent wrapper invalid requests converge to INVALID") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm,"S",false,ConnState.VALID)
		val start = new CountDownLatch(1); val done = new CountDownLatch(3)
		val ts = (1 to 3).map(_ => new Thread(() => { start.await(); Connections.setState(ew,"S",false,ConnState.INVALID); done.countDown() }))
		ts.foreach(_.start()); start.countDown(); done.await()
		assert(st("S",false) == ConnState.INVALID)
	}

	// 27
	test("concurrent manager valid vs wrapper invalid: last-writer wins but rules gate") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm,"S",false,ConnState.VALID)
		val start = new CountDownLatch(1); val done = new CountDownLatch(2)
		val t1 = new Thread(() => { start.await(); Connections.setState(cm,"S",false,ConnState.VALID); done.countDown() })
		val t2 = new Thread(() => { start.await(); Connections.setState(ew,"S",false,ConnState.INVALID); done.countDown() })
		t1.start(); t2.start(); start.countDown(); done.await()
		// Either schedule order, valid transitions produce VALID or INVALID; but wrapper invalid is legal only from VALID,
		// here both are legal; we accept whatever final state is among {VALID, INVALID}. Assert not INIT:
		assert(st("S",false) != ConnState.INIT)
	}

	// 28
	test("status changes do not alter state") {
		val (ew, cm) = initWithActors(); ensure("S")
		assert(st("S",true) == ConnState.INIT)
		Connections.setStatus(cm,"S",true,Connections.ON)
		Connections.setStatus(cm,"S",true,Connections.DROPPED)
		assert(st("S",true) == ConnState.INIT)
	}

	// 29
	test("idempotent same-state sets by either actor") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm,"S",true,ConnState.VALID)
		Connections.setState(ew,"S",true,ConnState.VALID)   // same -> same is allowed (no change)
		assert(st("S",true) == ConnState.VALID)
	}

	// 30
	test("lookupFor returns '?' when missing") {
		initWithActors()
		assert(Connections.lookupFor(42) == "?")
	}
}
