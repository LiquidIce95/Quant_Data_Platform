// File: src/test/scala/TestConnManagerShard.scala
package src.main.scala

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterEach

import com.ib.client._
import java.util.concurrent.atomic.AtomicReference

import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.{eq => meq, _}

final class TestConnManagerShard extends AnyFunSuite with BeforeAndAfterEach {

	private def newClient(): EClientSocket = mock(classOf[EClientSocket])
	private def newIo(c: EClientSocket): ClientIo = new ClientIo(c)

	private def seed(reqs: (Int, String)*): Unit = {
		reqs.foreach { case (rid, code) =>
			Connections.putLookup(rid, code)
			Connections.ensureEntry(code)
		}
	}

	private def wireActors(mgr: ConnManager): Unit = {
		val ew = new EwrapperImplementation(null)
		Connections.setActors(ew, mgr)
	}

	private def startAndWait(mgr: ConnManager, pollMs: Long = 250L, extra: Long = 250L): Unit = {
		mgr.start()
		mgr.startStreams(pollMs)
		Thread.sleep(math.max(350L, pollMs + extra))
	}

	private def stop(mgr: ConnManager): Unit = {
		mgr.stop()
		Thread.sleep(50L)
	}

	override protected def beforeEach(): Unit = {
		Connections.reset()
	}

	// ---------- Static strict-subset (single-entry map) ----------

	test("static subset: one in-shard, one out-of-shard (invalid) — out gets canceled only, in starts both") {
		val client = newClient()
		val io = newIo(client)

		val a = 3001 -> "CLZ5"
		val b = 3002 -> "NGZ5"
		seed(a, b)

		// shardingAlgorithm is a by-ref constant: returns Map("thisPod" -> List(a))
		val algoRef = new AtomicReference[Map[String, List[(Int, String)]]](Map("this" -> List(a)))
		val algo = (_: List[String]) => algoRef.get()

		val mgr = new ConnManager(client, io, "realtime", shardingAlgorithm = algo)
		wireActors(mgr)

		startAndWait(mgr)

		// in-shard 'a' started
		verify(client, atLeastOnce()).reqTickByTickData(meq(a._1), any(classOf[Contract]), meq("AllLast"), meq(0), meq(false))
		verify(client, atLeastOnce()).reqMktDepth(meq(a._1), any(classOf[Contract]), meq(12), meq(false), any())


		// out-of-shard 'b' (invalid by default): canceled, not started
		verify(client, atLeastOnce()).cancelTickByTickData(meq(b._1))
		verify(client, atLeastOnce()).cancelMktDepth(meq(b._1), meq(false))
		verify(client, never()).reqTickByTickData(meq(b._1), any(classOf[Contract]), anyString(), anyInt(), anyBoolean())
		verify(client, never()).reqMktDepth(meq(b._1), any(classOf[Contract]), anyInt(), anyBoolean(), any())

		stop(mgr)
	}

	test("static subset: out-of-shard VALID & DROPPED — no cancels, no starts") {
		val client = newClient()
		val io = newIo(client)

		val a = 3101 -> "CLF6"
		val b = 3102 -> "NGF6"
		seed(a, b)

		// prep b VALID & DROPPED using a throwaway manager as authorized actor
		val prepMgr = new ConnManager(client, io, "realtime")
		Connections.setActors(new EwrapperImplementation(null), prepMgr)
		Connections.setState(prepMgr, b._2, isL2 = false, ConnState.VALID)
		Connections.setState(prepMgr, b._2, isL2 = true,	ConnState.VALID)
		Connections.setStatus(prepMgr, b._2, isL2 = false, Connections.DROPPED)
		Connections.setStatus(prepMgr, b._2, isL2 = true,	Connections.DROPPED)

		val algoRef = new AtomicReference[Map[String, List[(Int, String)]]](Map("this" -> List(a)))
		val algo = (_: List[String]) => algoRef.get()

		val mgr = new ConnManager(client, io, "realtime", shardingAlgorithm = algo)
		wireActors(mgr)

		startAndWait(mgr)

		// out-of-shard case3: do not cancel valid legs, do not start
		verify(client, never()).cancelTickByTickData(meq(b._1))
		verify(client, never()).cancelMktDepth(meq(b._1), anyBoolean())
		verify(client, never()).reqTickByTickData(meq(b._1), any(classOf[Contract]), anyString(), anyInt(), anyBoolean())
		verify(client, never()).reqMktDepth(meq(b._1), any(classOf[Contract]), anyInt(), anyBoolean(), any())

		stop(mgr)
	}

	// ---------- Dynamic flips by swapping the constant algo ----------

	test("flip out -> in: previously out & invalid gets started when the algo changes to include it") {
		val client = newClient()
		val io = newIo(client)

		val a = 3201 -> "CLH6"
		val b = 3202 -> "NGH6"
		seed(a, b)

		// start with only 'a' in shard, then swap to include both
		val algoRef = new AtomicReference[Map[String, List[(Int, String)]]](Map("this" -> List(a)))
		val algo = (_: List[String]) => algoRef.get()

		val mgr = new ConnManager(client, io, "realtime", shardingAlgorithm = algo)
		wireActors(mgr)

		startAndWait(mgr)

		// out-of-shard 'b' invalid -> canceled, not started
		verify(client, atLeastOnce()).cancelTickByTickData(meq(b._1))
		verify(client, atLeastOnce()).cancelMktDepth(meq(b._1), meq(false))
		verify(client, never()).reqTickByTickData(meq(b._1), any(classOf[Contract]), anyString(), anyInt(), anyBoolean())
		verify(client, never()).reqMktDepth(meq(b._1), any(classOf[Contract]), anyInt(), anyBoolean(), any())

		// flip to include both
		algoRef.set(Map("this" -> List(a, b)))
		Thread.sleep(400L)

		// now b must be started (both legs)
		verify(client, atLeastOnce()).reqTickByTickData(meq(b._1), any(classOf[Contract]), meq("AllLast"), meq(0), meq(false))
		verify(client, atLeastOnce()).reqMktDepth(meq(b._1), any(classOf[Contract]), meq(12), meq(false), any())

		stop(mgr)
	}

	test("flip in -> out: valid & on becomes dropped only (no cancel); if a leg is invalid, that leg gets canceled") {
		val client = newClient()
		val io = newIo(client)

		val a = 3301 -> "CLJ6"
		val b = 3302 -> "NGJ6"
		seed(a, b)

		// start with both in shard
		val algoRef = new AtomicReference[Map[String, List[(Int, String)]]](Map("this" -> List(a, b)))
		val algo = (_: List[String]) => algoRef.get()

		val mgr = new ConnManager(client, io, "realtime", shardingAlgorithm = algo)
		wireActors(mgr)

		startAndWait(mgr)

		// simulate wrapper invalidating TBT for b (so next loop cancels TBT)
		val ew = new EwrapperImplementation(null)
		Connections.setActors(ew, mgr) // ew authorized to flip VALID -> INVALID
		Connections.setState(ew, b._2, isL2 = false, ConnState.INVALID)

		Thread.sleep(300L)
		verify(client, atLeastOnce()).cancelTickByTickData(meq(b._1))

		// now flip algo to drop b out of shard
		algoRef.set(Map("this" -> List(a)))
		Thread.sleep(400L)

		// out-of-shard case3: do not force-cancel valid legs (L2), and don't start anything for b
		verify(client, never()).cancelMktDepth(meq(b._1), anyBoolean())
		verify(client, never()).reqTickByTickData(meq(b._1), any(classOf[Contract]), anyString(), anyInt(), anyBoolean())
		verify(client, never()).reqMktDepth(meq(b._1), any(classOf[Contract]), anyInt(), anyBoolean(), any())

		stop(mgr)
	}
}
