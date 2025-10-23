// File: src/test/scala/TestConnStateTransition.scala
package src.main.scala

import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import java.util.concurrent.CountDownLatch

class TestConnStateTransition extends AnyFunSuite {

	// --- helpers -------------------------------------------------------------

	private def getState(cs: ConnState): ConnState.State = {
		val f = classOf[ConnState].getDeclaredField("connectionState")
		f.setAccessible(true)
		f.get(cs).asInstanceOf[ConnState.State]
	}

	private def named(s: ConnState.State): String = s match {
		case ConnState.INIT    => "INIT"
		case ConnState.VALID   => "VALID"
		case ConnState.INVALID => "INVALID"
		case ConnState.DROPPED => "DROPPED"
	}

	// --- fixtures ------------------------------------------------------------

	private def freshPair(): (ConnState, ConnState) = (new ConnState, new ConnState)

	// realistic shared map: symbol -> (tickByTick, L2)
	private def freshMap(): mutable.Map[String,(ConnState,ConnState)] = {
		val m = mutable.Map.empty[String,(ConnState,ConnState)]
		m.update("CL_SIM", freshPair())
		m
	}

	// --- tests ---------------------------------------------------------------

	test("manager sets INIT → VALID (ewrapper cannot)") {
		val sm = freshMap()
		val (tbt, l2) = sm("CL_SIM")

		// Ewrapper tries (must have no effect from INIT)
		tbt.asEwrapper -> ConnState.VALID
		assert(named(getState(tbt)) == "INIT")

		// Manager does it (allowed)
		tbt.asManager -> ConnState.VALID
		l2.asManager  -> ConnState.VALID

		assert(named(getState(tbt)) == "VALID")
		assert(named(getState(l2))  == "VALID")
	}

	test("race: manager VALID → DROPPED vs ewrapper VALID → INVALID lands on INVALID (tickByTick)") {
		val sm = freshMap()
		val (tbt, _) = sm("CL_SIM")

		// bootstrap to VALID by manager
		tbt.asManager -> ConnState.VALID
		assert(named(getState(tbt)) == "VALID")

		// race
		val start = new CountDownLatch(1)
		val done  = new CountDownLatch(2)

		val thMgr = new Thread(() => {
			start.await()
			tbt.asManager -> ConnState.DROPPED
			done.countDown()
		}, "mgr-thread")

		val thEw = new Thread(() => {
			start.await()
			tbt.asEwrapper -> ConnState.INVALID
			done.countDown()
		}, "ewrapper-thread")

		thMgr.start(); thEw.start()
		start.countDown()
		done.await()

		assert(named(getState(tbt)) == "INVALID")
	}

	test("DROPPED → INVALID by ewrapper is allowed (L2)") {
		val sm = freshMap()
		val (_, l2) = sm("CL_SIM")

		l2.asManager -> ConnState.VALID
		assert(named(getState(l2)) == "VALID")

		l2.asManager -> ConnState.DROPPED
		assert(named(getState(l2)) == "DROPPED")

		l2.asEwrapper -> ConnState.INVALID
		assert(named(getState(l2)) == "INVALID")
	}

	test("manager VALID → INVALID is allowed (e.g., shard rebalancing)") {
		val sm = freshMap()
		val (tbt, l2) = sm("CL_SIM")

		tbt.asManager -> ConnState.VALID
		l2.asManager  -> ConnState.VALID
		assert(named(getState(tbt)) == "VALID")
		assert(named(getState(l2))  == "VALID")

		tbt.asManager -> ConnState.INVALID
		l2.asManager  -> ConnState.INVALID

		assert(named(getState(tbt)) == "INVALID")
		assert(named(getState(l2))  == "INVALID")
	}

	test("illegal transitions are ignored: ewrapper INIT→VALID, manager DROPPED→VALID") {
		val sm = freshMap()
		val (tbt, l2) = sm("CL_SIM")

		// ewrapper cannot INIT → VALID
		tbt.asEwrapper -> ConnState.VALID
		assert(named(getState(tbt)) == "INIT")

		// move l2 to DROPPED first
		l2.asManager -> ConnState.VALID
		l2.asManager -> ConnState.DROPPED
		assert(named(getState(l2)) == "DROPPED")

		// manager should not be able to go DROPPED → VALID per the rules
		l2.asManager -> ConnState.VALID
		assert(named(getState(l2)) == "DROPPED")

		// ewrapper can finalize to INVALID
		l2.asEwrapper -> ConnState.INVALID
		assert(named(getState(l2)) == "INVALID")
	}

	test("concurrent flurry still stabilizes at INVALID") {
		val sm = freshMap()
		val (tbt, _) = sm("CL_SIM")

		// set VALID
		tbt.asManager -> ConnState.VALID
		assert(named(getState(tbt)) == "VALID")

		// fire multiple contenders
		val start = new CountDownLatch(1)
		val done  = new CountDownLatch(4)

		val threads = Seq[Thread](
			new Thread(() => { start.await(); tbt.asManager  -> ConnState.DROPPED;  done.countDown() }, "mgr-drop"),
			new Thread(() => { start.await(); tbt.asEwrapper -> ConnState.INVALID;  done.countDown() }, "ewr-inv-1"),
			new Thread(() => { start.await(); tbt.asManager  -> ConnState.INVALID;  done.countDown() }, "mgr-inv"),
			new Thread(() => { start.await(); tbt.asEwrapper -> ConnState.INVALID;  done.countDown() }, "ewr-inv-2")
		)
		threads.foreach(_.start())
		start.countDown()
		done.await()

		assert(named(getState(tbt)) == "INVALID")
	}
}
