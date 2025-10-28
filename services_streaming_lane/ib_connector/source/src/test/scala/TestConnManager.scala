// File: src/test/scala/TestConnManager.scala
package src.main.scala

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterEach

import com.ib.client._
import java.util.concurrent.atomic.AtomicReference

import org.mockito.Mockito._
import org.mockito.ArgumentMatchers.{eq => meq, _}

import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.api.model.{Pod, PodList, PodBuilder}
import io.fabric8.kubernetes.client.dsl.{MixedOperation, NonNamespaceOperation, PodResource}

/**
  * Deterministic tests:
  *  - We drive exactly ONE supervisor iteration by using a large poll interval and a short sleep.
  *  - For sharding tests, we inject a mocked Fabric8 client so the K8s call chain returns a concrete PodList.
  *  - We verify EXACT invocation counts with Mockito.times(n).
  */
final class TestConnManager extends AnyFunSuite with BeforeAndAfterEach {

	// ---------- helpers ----------

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

	/** Run exactly one supervisor loop by picking a big poll interval. */
	private def runOneLoop(mgr: ConnManager): Unit = {
		mgr.start()
		val pollMs = 800L
		mgr.startStreams(pollMs)
		Thread.sleep(400L) // enough for one pass, well under pollMs to avoid a 2nd
	}

	/** Allow one more loop after state/algo changes. */
	private def runNextLoop(): Unit = Thread.sleep(600L)

	private def stop(mgr: ConnManager): Unit = {
		mgr.stop()
		Thread.sleep(50L)
	}

	override protected def beforeEach(): Unit = {
		Connections.reset()
		System.setProperty("pod.name", "this")
		System.setProperty("kubernetes.namespace", "ib-connector")
	}

	override protected def afterEach(): Unit = {
		System.clearProperty("pod.name")
		System.clearProperty("kubernetes.namespace")
	}

	/**
	  * Inject a mocked Fabric8 client so that pods().inNamespace(any).list() returns a PodList
	  * containing a single dummy pod (or more, if names provided). No deep-stub magic.
	  */
	private def injectPeersAnyNs(mgr: ConnManager, peerNames: Seq[String]): Unit = {
		val k8s = mock(classOf[KubernetesClient])

		val mixed = mock(classOf[MixedOperation[Pod, PodList, PodResource]])
		val nonNs = mock(classOf[NonNamespaceOperation[Pod, PodList, PodResource]])

		when(k8s.pods()).thenReturn(mixed)
		when(mixed.inNamespace(anyString())).thenReturn(nonNs)

		val podList = new PodList()
		val items = new java.util.ArrayList[Pod]()
		val names = if (peerNames.isEmpty) Seq("dummy-pod") else peerNames
		names.foreach { name =>
			items.add(new PodBuilder().withNewMetadata().withName(name).endMetadata().build())
		}
		podList.setItems(items)

		when(nonNs.list()).thenReturn(podList)

		val f = classOf[ConnManager].getDeclaredField("kubernetesClient")
		f.setAccessible(true)
		f.set(mgr, k8s)
	}

	private def injectEmptyPeersAnyNs(mgr: ConnManager): Unit =
		injectPeersAnyNs(mgr, Seq("dummy-pod"))

	// ======================================================================
	// SECTION A — State algorithm (no shardingAlgorithm -> full universe)
	// ======================================================================

	test("[A1] both legs INVALID -> VALID and start both (realtime); exact calls & final states/status") {
		val client = newClient()
		val io = newIo(client)
		val reqId = 101; val code = "CLZ5"
		seed(reqId -> code)

		val mgr = new ConnManager(client, io, "realtime", null)
		wireActors(mgr)

		runOneLoop(mgr)

		// exact invocations
		verify(client, times(1)).reqMarketDataType(1)
		verify(client, times(1)).cancelTickByTickData(meq(reqId))
		verify(client, times(1)).cancelMktDepth(meq(reqId), meq(false))
		verify(client, times(1)).reqTickByTickData(meq(reqId), any(classOf[Contract]), meq("AllLast"), meq(0), meq(false))
		verify(client, times(1)).reqMktDepth(meq(reqId), any(classOf[Contract]), meq(12), meq(false), any())

		// final states/status
		assert(Connections.stateOf(code, isL2 = false) == ConnState.VALID)
		assert(Connections.stateOf(code, isL2 = true)  == ConnState.VALID)
		assert(Connections.statusOf(code, isL2 = false) == Connections.ON)
		assert(Connections.statusOf(code, isL2 = true)  == Connections.ON)

		stop(mgr)
	}

	test("[A2] only TBT INVALID (L2 VALID) -> cancel TBT once, start TBT once; L2 untouched") {
		val client = newClient()
		val io = newIo(client)
		val reqId = 201; val code = "NGZ5"
		seed(reqId -> code)

		val mgr = new ConnManager(client, io, "realtime", null)
		wireActors(mgr)

		Connections.setState(mgr, code, isL2 = true, ConnState.VALID)

		runOneLoop(mgr)

		verify(client, times(1)).reqMarketDataType(1)
		verify(client, times(1)).cancelTickByTickData(meq(reqId))
		verify(client, times(0)).cancelMktDepth(anyInt(), anyBoolean())
		verify(client, times(1)).reqTickByTickData(meq(reqId), any(classOf[Contract]), meq("AllLast"), meq(0), meq(false))
		verify(client, times(0)).reqMktDepth(anyInt(), any(classOf[Contract]), anyInt(), anyBoolean(), any())

		assert(Connections.stateOf(code, isL2 = false) == ConnState.VALID)
		assert(Connections.stateOf(code, isL2 = true)  == ConnState.VALID)
		assert(Connections.statusOf(code, isL2 = false) == Connections.ON)
		assert(Connections.statusOf(code, isL2 = true)  == Connections.ON)

		stop(mgr)
	}

	test("[A3] only L2 INVALID (TBT VALID) -> cancel L2 once, start L2 once; TBT untouched") {
		val client = newClient()
		val io = newIo(client)
		val reqId = 301; val code = "CLF6"
		seed(reqId -> code)

		val mgr = new ConnManager(client, io, "realtime", null)
		wireActors(mgr)

		Connections.setState(mgr, code, isL2 = false, ConnState.VALID)

		runOneLoop(mgr)

		verify(client, times(1)).reqMarketDataType(1)
		verify(client, times(0)).cancelTickByTickData(anyInt())
		verify(client, times(1)).cancelMktDepth(meq(reqId), meq(false))
		verify(client, times(0)).reqTickByTickData(anyInt(), any(classOf[Contract]), anyString(), anyInt(), anyBoolean())
		verify(client, times(1)).reqMktDepth(meq(reqId), any(classOf[Contract]), meq(12), meq(false), any())

		assert(Connections.stateOf(code, isL2 = false) == ConnState.VALID)
		assert(Connections.stateOf(code, isL2 = true)  == ConnState.VALID)
		assert(Connections.statusOf(code, isL2 = false) == Connections.ON)
		assert(Connections.statusOf(code, isL2 = true)  == Connections.ON)

		stop(mgr)
	}

	test("[A4] both VALID & ON -> no cancels, no restarts (only marketDataType once)") {
		val client = newClient()
		val io = newIo(client)
		val reqId = 401; val code = "NGF6"
		seed(reqId -> code)

		val mgr = new ConnManager(client, io, "realtime", null)
		wireActors(mgr)

		Connections.setState(mgr, code, isL2 = false, ConnState.VALID)
		Connections.setState(mgr, code, isL2 = true,  ConnState.VALID)
		Connections.setStatus(mgr, code, isL2 = false, Connections.ON)
		Connections.setStatus(mgr, code, isL2 = true,  Connections.ON)

		runOneLoop(mgr)

		verify(client, times(1)).reqMarketDataType(1)
		verify(client, times(0)).cancelTickByTickData(anyInt())
		verify(client, times(0)).cancelMktDepth(anyInt(), anyBoolean())
		verify(client, times(0)).reqTickByTickData(anyInt(), any(classOf[Contract]), anyString(), anyInt(), anyBoolean())
		verify(client, times(0)).reqMktDepth(anyInt(), any(classOf[Contract]), anyInt(), anyBoolean(), any())

		assert(Connections.stateOf(code, isL2 = false) == ConnState.VALID)
		assert(Connections.stateOf(code, isL2 = true)  == ConnState.VALID)
		assert(Connections.statusOf(code, isL2 = false) == Connections.ON)
		assert(Connections.statusOf(code, isL2 = true)  == Connections.ON)

		stop(mgr)
	}

	// ======================================================================
	// SECTION B — With shardingAlgorithm (changing podSymbols)
	// ======================================================================

	test("[B1] static subset: in-shard 'a' starts; out-of-shard 'b' cancels only; exact counts & final states") {
		val client = newClient(); val io = newIo(client)
		val a = 5001 -> "CLZ5"; val b = 5002 -> "NGZ5"
		seed(a, b)

		val ref = new AtomicReference[Map[String, List[(Int, String)]]](Map("this" -> List(a)))
		val algo: (List[String], Map[String, List[(Int, String)]]) => Map[String, List[(Int, String)]] =
			(_peers, _cur) => ref.get()

		val mgr = new ConnManager(client, io, "realtime", shardingAlgorithm = algo)
		wireActors(mgr)
		injectPeersAnyNs(mgr, Seq("this", "p2"))

		runOneLoop(mgr)

		// Step1 cancels INVALID legs for both a & b (2 reqs)
		verify(client, times(2)).cancelTickByTickData(anyInt())
		verify(client, times(2)).cancelMktDepth(anyInt(), meq(false))
		// Starts only for a (both legs)
		verify(client, times(1)).reqTickByTickData(meq(a._1), any(classOf[Contract]), meq("AllLast"), meq(0), meq(false))
		verify(client, times(1)).reqMktDepth(meq(a._1), any(classOf[Contract]), meq(12), meq(false), any())
		// No starts for b
		verify(client, times(0)).reqTickByTickData(meq(b._1), any(classOf[Contract]), anyString(), anyInt(), anyBoolean())
		verify(client, times(0)).reqMktDepth(meq(b._1), any(classOf[Contract]), anyInt(), anyBoolean(), any())
		verify(client, times(1)).reqMarketDataType(1)

		// Final states/status
		assert(Connections.stateOf(a._2, isL2 = false) == ConnState.VALID)
		assert(Connections.stateOf(a._2, isL2 = true)  == ConnState.VALID)
		assert(Connections.statusOf(a._2, isL2 = false) == Connections.ON)
		assert(Connections.statusOf(a._2, isL2 = true)  == Connections.ON)

		assert(Connections.stateOf(b._2, isL2 = false) == ConnState.INVALID)
		assert(Connections.stateOf(b._2, isL2 = true)  == ConnState.INVALID)
		assert(Connections.statusOf(b._2, isL2 = false) == Connections.DROPPED)
		assert(Connections.statusOf(b._2, isL2 = true)  == Connections.DROPPED)

		stop(mgr)
	}

	test("[B2] static subset: out-of-shard 'b' is VALID & DROPPED — no cancels or starts for b; 'a' starts both") {
		val client = newClient(); val io = newIo(client)
		val a = 5101 -> "CLF6"; val b = 5102 -> "NGF6"
		seed(a, b)

		// Prep b VALID & DROPPED
		val prepMgr = new ConnManager(client, io, "realtime", null)
		Connections.setActors(new EwrapperImplementation(null), prepMgr)
		Connections.setState(prepMgr, b._2, isL2 = false, ConnState.VALID)
		Connections.setState(prepMgr, b._2, isL2 = true,  ConnState.VALID)
		Connections.setStatus(prepMgr, b._2, isL2 = false, Connections.DROPPED)
		Connections.setStatus(prepMgr, b._2, isL2 = true,  Connections.DROPPED)

		val ref = new AtomicReference[Map[String, List[(Int, String)]]](Map("this" -> List(a)))
		val algo: (List[String], Map[String, List[(Int, String)]]) => Map[String, List[(Int, String)]] =
			(_peers, _cur) => ref.get()

		val mgr = new ConnManager(client, io, "realtime", shardingAlgorithm = algo)
		wireActors(mgr)
		injectPeersAnyNs(mgr, Seq("this"))

		runOneLoop(mgr)

		// Cancels for a only (both legs)
		verify(client, times(1)).cancelTickByTickData(meq(a._1))
		verify(client, times(1)).cancelMktDepth(meq(a._1), meq(false))
		// No cancels for b
		verify(client, times(0)).cancelTickByTickData(meq(b._1))
		verify(client, times(0)).cancelMktDepth(meq(b._1), anyBoolean())
		// Starts for a only
		verify(client, times(1)).reqTickByTickData(meq(a._1), any(classOf[Contract]), meq("AllLast"), meq(0), meq(false))
		verify(client, times(1)).reqMktDepth(meq(a._1), any(classOf[Contract]), meq(12), meq(false), any())
		// No starts for b
		verify(client, times(0)).reqTickByTickData(meq(b._1), any(classOf[Contract]), anyString(), anyInt(), anyBoolean())
		verify(client, times(0)).reqMktDepth(meq(b._1), any(classOf[Contract]), anyInt(), anyBoolean(), any())
		verify(client, times(1)).reqMarketDataType(1)

		// Final states/status
		assert(Connections.stateOf(a._2, isL2 = false) == ConnState.VALID)
		assert(Connections.stateOf(a._2, isL2 = true)  == ConnState.VALID)
		assert(Connections.statusOf(a._2, isL2 = false) == Connections.ON)
		assert(Connections.statusOf(a._2, isL2 = true)  == Connections.ON)

		assert(Connections.stateOf(b._2, isL2 = false) == ConnState.VALID)
		assert(Connections.stateOf(b._2, isL2 = true)  == ConnState.VALID)
		assert(Connections.statusOf(b._2, isL2 = false) == Connections.DROPPED)
		assert(Connections.statusOf(b._2, isL2 = true)  == Connections.DROPPED)

		stop(mgr)
	}

	test("[B3] flip out -> in: first loop starts 'a' only; second loop starts 'b' (exact totals)") {
		val client = newClient(); val io = newIo(client)
		val a = 5201 -> "CLH6"; val b = 5202 -> "NGH6"
		seed(a, b)

		val ref = new AtomicReference[Map[String, List[(Int, String)]]](Map("this" -> List(a)))
		val algo: (List[String], Map[String, List[(Int, String)]]) => Map[String, List[(Int, String)]] =
			(_peers, _cur) => ref.get()

		val mgr = new ConnManager(client, io, "realtime", shardingAlgorithm = algo)
		wireActors(mgr)
		injectPeersAnyNs(mgr, Seq("this","p2"))

		// 1st loop: cancels a & b (2 each), starts a (both legs)
		runOneLoop(mgr)

		// flip to include b, 2nd loop
		ref.set(Map("this" -> List(a, b)))
		runNextLoop()

		// Totals after two loops:
		// Cancels: first loop a+b = 2; second loop b still INVALID -> +1  => 3 each leg
		verify(client, times(3)).cancelTickByTickData(anyInt())
		verify(client, times(3)).cancelMktDepth(anyInt(), meq(false))
		// Starts: a once (first), b once (second)
		verify(client, times(2)).reqTickByTickData(anyInt(), any(classOf[Contract]), meq("AllLast"), meq(0), meq(false))
		verify(client, times(2)).reqMktDepth(anyInt(), any(classOf[Contract]), meq(12), meq(false), any())
		verify(client, atLeast(1)).reqMarketDataType(1) // can be called once; allow >=1

		// Final states/status — both VALID & ON
		assert(Connections.stateOf(a._2, isL2 = false) == ConnState.VALID)
		assert(Connections.stateOf(a._2, isL2 = true)  == ConnState.VALID)
		assert(Connections.statusOf(a._2, isL2 = false) == Connections.ON)
		assert(Connections.statusOf(a._2, isL2 = true)  == Connections.ON)

		assert(Connections.stateOf(b._2, isL2 = false) == ConnState.VALID)
		assert(Connections.stateOf(b._2, isL2 = true)  == ConnState.VALID)
		assert(Connections.statusOf(b._2, isL2 = false) == Connections.ON)
		assert(Connections.statusOf(b._2, isL2 = true)  == Connections.ON)

		stop(mgr)
	}

	test("[B4] flip in -> out: start both for a&b, then invalidate b/TBT and drop b — exact totals & final states") {
		val client = newClient(); val io = newIo(client)
		val a = 5301 -> "CLJ6"; val b = 5302 -> "NGJ6"
		seed(a, b)

		val ref = new AtomicReference[Map[String, List[(Int, String)]]](Map("this" -> List(a, b)))
		val algo: (List[String], Map[String, List[(Int, String)]]) => Map[String, List[(Int, String)]] =
			(_peers, _cur) => ref.get()

		val mgr = new ConnManager(client, io, "realtime", shardingAlgorithm = algo)
		wireActors(mgr)
		injectPeersAnyNs(mgr, Seq("this","p2"))

		// 1st loop: both invalid -> cancel both legs for a & b, then start both legs for a & b
		runOneLoop(mgr)

		// Invalidate TBT for b (wrapper-only transition), then drop b out of shard
		val ew = new EwrapperImplementation(null)
		Connections.setActors(ew, mgr)
		Connections.setState(ew, b._2, isL2 = false, ConnState.INVALID)
		ref.set(Map("this" -> List(a)))
		runNextLoop()

		// Totals:
		// First loop: cancelTick 2 (a,b), cancelL2 2 (a,b), startTick 2, startL2 2
		// Second loop: b/TBT invalid -> one more cancelTick (+1) = 3 total; no extra L2 cancel
		verify(client, times(3)).cancelTickByTickData(anyInt())
		verify(client, times(2)).cancelMktDepth(anyInt(), meq(false))
		verify(client, times(2)).reqTickByTickData(anyInt(), any(classOf[Contract]), meq("AllLast"), meq(0), meq(false))
		verify(client, times(2)).reqMktDepth(anyInt(), any(classOf[Contract]), meq(12), meq(false), any())
		verify(client, atLeast(1)).reqMarketDataType(1)

		// Final states/status:
		// a stays VALID & ON
		assert(Connections.stateOf(a._2, isL2 = false) == ConnState.VALID)
		assert(Connections.stateOf(a._2, isL2 = true)  == ConnState.VALID)
		assert(Connections.statusOf(a._2, isL2 = false) == Connections.ON)
		assert(Connections.statusOf(a._2, isL2 = true)  == Connections.ON)

		// b is out-of-shard: DROPPED both; states: TBT INVALID (we forced), L2 VALID from first loop
		assert(Connections.stateOf(b._2, isL2 = false) == ConnState.INVALID)
		assert(Connections.stateOf(b._2, isL2 = true)  == ConnState.VALID)
		assert(Connections.statusOf(b._2, isL2 = false) == Connections.DROPPED)
		assert(Connections.statusOf(b._2, isL2 = true)  == Connections.DROPPED)

		stop(mgr)
	}
}
