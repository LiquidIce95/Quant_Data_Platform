// File: src/main/scala/Connections.scala
package src.main.scala

import scala.collection.mutable

/**
  * Singleton that centralizes:
  *  - lookupMap: reqId -> tradingSymbol
  *  - stateMap:  tradingSymbol -> (ConnState for TickByTick, ConnState for L2)
  *  - statusMap: tradingSymbol -> (ConnStatus for TickByTick, ConnStatus for L2)
  *  - the ONLY legal actor instances that can mutate state/status.
  *
  * Rules enforced here:
  *  - Only the ConnManager instance can transition:
  *      INIT -> VALID
  *      any  -> INVALID   (manager is allowed)
  *  - Only the Ewrapper instance can transition:
  *      VALID -> INVALID
  *  - ConnStatus (ON/DROPPED) can be changed ONLY by ConnManager.
  *
  * Ewrapper and ConnManager never touch ConnState/ConnStatus directly; they call into this API.
  */
object Connections {

	object ConnStatus extends Enumeration {
		val ON, DROPPED = Value
	}

	// shared tables
	private val lookupMap: mutable.Map[Int, String] = mutable.HashMap.empty[Int, String]
	private val stateMap:  mutable.Map[String,(ConnState, ConnState)] = mutable.HashMap.empty[String,(ConnState,ConnState)]
	private val statusMap: mutable.Map[String,(ConnStatus.Value, ConnStatus.Value)] = mutable.HashMap.empty[String,(ConnStatus.Value,ConnStatus.Value)]

	// the only authorized instances
	@volatile private var ewRef: EwrapperImplementation = null
	@volatile private var cmRef: ConnManager = null

	// ---- lifecycle ---------------------------------------------------------

	def reset(): Unit = {
		lookupMap.clear()
		stateMap.clear()
		statusMap.clear()
		ewRef = null
		cmRef = null
	}

	def setActors(ew: EwrapperImplementation, cm: ConnManager): Unit = {
		ewRef = ew
		cmRef = cm
	}

	// ---- discovery helpers -------------------------------------------------

	def putLookup(reqId: Int, code: String): Unit = this.synchronized {
        lookupMap.update(reqId, code)
    }
    
	def ensureEntry(code: String): Unit = this.synchronized {
		if (!stateMap.contains(code)) stateMap.update(code, (new ConnState, new ConnState))
		if (!statusMap.contains(code)) statusMap.update(code, (ConnStatus.DROPPED, ConnStatus.DROPPED))
	}

	// ---- reads -------------------------------------------------------------

	def codes: Iterable[String] = stateMap.keys

	def entriesSortedByReqId: Seq[(Int,String)] =
		lookupMap.toSeq.sortBy(_._1)

	def stateOf(code: String, isL2: Boolean): Option[ConnState.State] =
		stateMap.get(code).map { case (tbt,l2) => if (isL2) l2.get else tbt.get }

	def statusOf(code: String, isL2: Boolean): Option[ConnStatus.Value] =
		statusMap.get(code).map { case (tbt,l2) => if (isL2) l2 else tbt }

	def lookupFor(reqId: Int): String = lookupMap.getOrElse(reqId, "?")

	def discoveryEmpty: Boolean = lookupMap.isEmpty || stateMap.isEmpty

	// ---- writes: guarded by actor identity --------------------------------

	/**
	  * Change ConnState for a specific code/leg (tbt or l2).
	  * @param caller must be exactly ewRef or cmRef; otherwise ignored.
	  * @param code   trading symbol
	  * @param isL2   false=tick-by-tick leg, true=L2 leg
	  * @param target desired state (INIT, VALID, INVALID)
	  */
	def setState(caller: AnyRef, code: String, isL2: Boolean, target: ConnState.State): Unit = this.synchronized {
		val whoIsManager = caller eq cmRef
		val whoIsEw      = caller eq ewRef
		if (!whoIsManager && !whoIsEw) return // unauthorized

		stateMap.get(code) match {
			case None => () // unknown code
			case Some((tbt, l2)) =>
				val cs = if (isL2) l2 else tbt
				val cur = cs.get
				val legal =
					(cur, target) match {
						case (ConnState.INIT,  ConnState.VALID)   => whoIsManager
						case (ConnState.VALID, ConnState.INVALID) => whoIsManager || whoIsEw
						case (ConnState.INVALID, ConnState.INVALID) => true // idempotent
						case (_, ConnState.INIT) => false // never go back to INIT
						case (ConnState.INIT, ConnState.INVALID) => whoIsManager || whoIsEw // allow direct invalidation
						case (ConnState.VALID, ConnState.VALID)   => true  // idempotent
						case (ConnState.INVALID, ConnState.VALID) => false // no resurrection here
						case _ => false
					}
				if (legal) cs.unsafeSet(target)
		}
	}

	/**
	  * Change connection status (ON/DROPPED) for a specific code/leg.
	  * Only ConnManager may change status.
	  */
	def setStatus(caller: AnyRef, code: String, isL2: Boolean, target: ConnStatus.Value): Unit = this.synchronized {
		if (!(caller eq cmRef)) return
		statusMap.get(code) match {
			case None => ()
			case Some((tbt, l2)) =>
				if (isL2) statusMap.update(code, (tbt, target))
				else      statusMap.update(code, (target, l2))
		}
	}
}
