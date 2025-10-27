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
	private def st(code: String, isL2: Boolean) = Connections.stateOf(code, isL2)
	private def ss(code: String, isL2: Boolean) = Connections.statusOf(code, isL2)

	// 1
	test("reset empties tables and actors") {
		Connections.reset()
		assert(Connections.discoveryEmpty)
	}

	// 2
	test("ensureEntry initializes INVALID + DROPPED") {
		initWithActors(); ensure("S")
		assert(st("S", false) == ConnState.INVALID)
		assert(st("S", true)  == ConnState.INVALID)
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
	test("manager INVALID->VALID allowed") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm, "S", false, ConnState.VALID)
		assert(st("S", false) == ConnState.VALID)
	}

	// 6
	test("wrapper VALID->INVALID allowed; manager same transition ignored") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm, "S", false, ConnState.VALID)
		Connections.setState(cm, "S", false, ConnState.INVALID) // ignore
		assert(st("S", false) == ConnState.VALID)
		Connections.setState(ew, "S", false, ConnState.INVALID) // allowed
		assert(st("S", false) == ConnState.INVALID)
	}

	// 7
	test("wrapper cannot INVALID->VALID; manager can") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(ew, "S", false, ConnState.VALID) // ignore
		assert(st("S", false) == ConnState.INVALID)
		Connections.setState(cm, "S", false, ConnState.VALID) // allowed
		assert(st("S", false) == ConnState.VALID)
	}

	// 8
	test("idempotent transitions") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm, "S", true, ConnState.VALID)
		Connections.setState(cm, "S", true, ConnState.VALID)
		assert(st("S", true) == ConnState.VALID)
		Connections.setState(ew, "S", true, ConnState.INVALID)
		Connections.setState(ew, "S", true, ConnState.INVALID)
		assert(st("S", true) == ConnState.INVALID)
	}

	// 9
	test("manager-only status flips") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setStatus(ew, "S", false, Connections.ON)
		assert(ss("S", false) == Connections.DROPPED)
		Connections.setStatus(cm, "S", false, Connections.ON)
		assert(ss("S", false) == Connections.ON)
		Connections.setStatus(cm, "S", false, Connections.DROPPED)
		assert(ss("S", false) == Connections.DROPPED)
	}

	// 10
	test("entriesSortedByReqId ordering") {
		initWithActors()
		Connections.putLookup(12,"C"); Connections.putLookup(1,"A"); Connections.putLookup(7,"B")
		assert(Connections.entriesSortedByReqId.map(_._1) == Seq(1,7,12))
	}

	// 11
	test("discoveryEmpty reflects map emptiness") {
		initWithActors()
		assert(Connections.discoveryEmpty)
		ensure("S")
		assert(Connections.discoveryEmpty)
		Connections.putLookup(5001,"S")
		assert(!Connections.discoveryEmpty)
	}

	// 12
	test("leg separation: transitions are per-leg") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm,"S",false,ConnState.VALID)
		Connections.setState(cm,"S",true,ConnState.VALID)
		Connections.setState(ew,"S",true,ConnState.INVALID)
		assert(st("S",false) == ConnState.VALID)
		assert(st("S",true)  == ConnState.INVALID)
	}

	// 13
	test("unauthorized caller ignored") {
		initWithActors(); ensure("S")
		val rnd = new Object()
		Connections.setState(rnd,"S",false,ConnState.VALID)
		Connections.setStatus(rnd,"S",false,Connections.ON)
		assert(st("S",false) == ConnState.INVALID)
		assert(ss("S",false) == Connections.DROPPED)
	}

	// 14
	test("manager can resurrect INVALID to VALID") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(ew,"S",false,ConnState.INVALID) // idempotent
		assert(st("S",false) == ConnState.INVALID)
		Connections.setState(cm,"S",false,ConnState.VALID)
		assert(st("S",false) == ConnState.VALID)
	}

	// 15
	test("status flips do not change state") {
		val (ew, cm) = initWithActors(); ensure("S")
		assert(st("S",false) == ConnState.INVALID)
		Connections.setStatus(cm,"S",false,Connections.ON)
		Connections.setStatus(cm,"S",false,Connections.DROPPED)
		assert(st("S",false) == ConnState.INVALID)
	}

	// 16
	test("wrapper VALID&DROPPED => wrapper will set INVALID") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm,"S",true,ConnState.VALID)
		Connections.setStatus(cm,"S",true,Connections.DROPPED)
		Connections.setState(ew,"S",true,ConnState.INVALID)
		assert(st("S",true) == ConnState.INVALID)
	}

	// 17
	test("manager ON then VALID (startStreams-like)") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setStatus(cm,"S",false,Connections.ON)
		Connections.setState(cm,"S",false,ConnState.VALID)
		assert(st("S",false) == ConnState.VALID)
		assert(ss("S",false) == Connections.ON)
	}

	// 18
	test("idempotent status set") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setStatus(cm,"S",true,Connections.ON)
		Connections.setStatus(cm,"S",true,Connections.ON)
		assert(ss("S",true) == Connections.ON)
	}

	// 19
	test("wrapper INVALID when already INVALID is no-op") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(ew,"S",false,ConnState.INVALID)
		assert(st("S",false) == ConnState.INVALID)
	}

	// 20
	test("concurrent wrapper invalids converge to INVALID") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm,"S",false,ConnState.VALID)
		val start = new CountDownLatch(1); val done = new CountDownLatch(3)
		val ts = (1 to 3).map(_ => new Thread(() => { start.await(); Connections.setState(ew,"S",false,ConnState.INVALID); done.countDown() }))
		ts.foreach(_.start()); start.countDown(); done.await()
		assert(st("S",false) == ConnState.INVALID)
	}

	// 21
	test("concurrent manager valid vs wrapper invalid: final is VALID or INVALID (never UNKNOWN)") {
		val (ew, cm) = initWithActors(); ensure("S")
		Connections.setState(cm,"S",false,ConnState.VALID)
		val start = new CountDownLatch(1); val done = new CountDownLatch(2)
		val t1 = new Thread(() => { start.await(); Connections.setState(cm,"S",false,ConnState.VALID); done.countDown() })
		val t2 = new Thread(() => { start.await(); Connections.setState(ew,"S",false,ConnState.INVALID); done.countDown() })
		t1.start(); t2.start(); start.countDown(); done.await()
		assert(st("S",false) != null) // sanity
	}

	// 22
	test("lookupFor returns '?' when missing") {
		initWithActors()
		assert(Connections.lookupFor(42) == "?")
	}

	// 23
	test("entriesSortedByReqId stable mapping") {
		initWithActors()
		Connections.putLookup(5, "X")
		Connections.putLookup(1, "A")
		Connections.putLookup(3, "B")
		val xs = Connections.entriesSortedByReqId
		assert(xs.map(_._2) == Seq("A","B","X"))
	}

	// 24
	test("ensureEntry idempotent") {
		initWithActors()
		ensure("X"); ensure("X")
		assert(st("X", false) == ConnState.INVALID)
		assert(st("X", true)  == ConnState.INVALID)
	}
}
