// File: src/main/scala/Connections.scala
package src.main.scala

import scala.collection.mutable

/**
  * Singleton: owns discovery maps and enforces *who* may change *what*.
  *
  * Rules:
  *   - State transitions:
  *       INIT  -> VALID   (manager only)
  *       VALID -> INVALID (wrapper only)
  *       INVALID -> VALID (manager only)
  *     Same->Same is idempotent (allowed). All others are ignored.
  *   - Status (ON/DROPPED): manager only.
  *   - Unknown code on any mutation => IllegalStateException.
  */
object Connections {

	// Stronger typing than Enumeration
	sealed trait ConnStatus
	case object ON extends ConnStatus
	case object DROPPED extends ConnStatus

	// tables
	private val lookupMap: mutable.Map[Int, String] = mutable.HashMap.empty
	private val stateMap:  mutable.Map[String,(ConnState, ConnState)] = mutable.HashMap.empty
	private val statusMap: mutable.Map[String,(ConnStatus, ConnStatus)] = mutable.HashMap.empty

	// authorized actors
	@volatile private var ewRef: EwrapperImplementation = null
	@volatile private var cmRef: ConnManager = null

	// -------- lifecycle -----------------------------------------------------

	def reset(): Unit = this.synchronized {
		lookupMap.clear(); stateMap.clear(); statusMap.clear()
		ewRef = null; cmRef = null
	}

	def setActors(ew: EwrapperImplementation, cm: ConnManager): Unit = this.synchronized {
		ewRef = ew; cmRef = cm
	}

	// -------- discovery helpers --------------------------------------------

	def putLookup(reqId: Int, code: String): Unit = this.synchronized {
		lookupMap.update(reqId, code)
	}

	def ensureEntry(code: String): Unit = this.synchronized {
		if (!stateMap.contains(code)) stateMap.update(code, (new ConnState, new ConnState))
		if (!statusMap.contains(code)) statusMap.update(code, (DROPPED, DROPPED))
	}

	// -------- reads ---------------------------------------------------------

	def codes: Iterable[String] = this.synchronized { stateMap.keys.toList }

	def entriesSortedByReqId: Seq[(Int,String)] = this.synchronized {
		lookupMap.toSeq.sortBy(_._1)
	}

	def stateOf(code: String, isL2: Boolean): Option[ConnState.State] = this.synchronized {
		stateMap.get(code).map { case (tbt,l2) => if (isL2) l2.get else tbt.get }
	}

	def statusOf(code: String, isL2: Boolean): Option[ConnStatus] = this.synchronized {
		statusMap.get(code).map { case (tbt,l2) => if (isL2) l2 else tbt }
	}

	def lookupFor(reqId: Int): String = this.synchronized {
		lookupMap.getOrElse(reqId, "?")
	}

	def discoveryEmpty: Boolean = this.synchronized {
		lookupMap.isEmpty || stateMap.isEmpty || statusMap.isEmpty
	}

	// -------- writes (guarded + validated) ---------------------------------

	private def requireKnown(code: String): Unit = {
		if (!stateMap.contains(code) || !statusMap.contains(code))
			throw new IllegalStateException(s"Connections: unknown code=$code")
	}

	/**
	  * Apply state transition if allowed by rules.
	  * @param caller ewRef or cmRef; others ignored.
	  * @throws IllegalStateException if code unknown
	  */
	def setState(caller: AnyRef, code: String, isL2: Boolean, target: ConnState.State): Unit = this.synchronized {
		requireKnown(code)
		val isMgr = caller eq cmRef
		val isEw  = caller eq ewRef
		if (!isMgr && !isEw) return

		val (tbt, l2) = stateMap(code)
		val cs  = if (isL2) l2 else tbt
		val cur = cs.get

		val legal = (cur, target) match {
			case (x, y) if x == y                  => true // idempotent
			case (ConnState.INIT,    ConnState.VALID)   => isMgr
			case (ConnState.VALID,   ConnState.INVALID) => isEw
			case (ConnState.INVALID, ConnState.VALID)   => isMgr
			case _ => false
		}
		if (legal) cs.unsafeSet(target)
	}

	/**
	  * Change ON/DROPPED (manager only).
	  * @throws IllegalStateException if code unknown
	  */
	def setStatus(caller: AnyRef, code: String, isL2: Boolean, target: ConnStatus): Unit = this.synchronized {
		requireKnown(code)
		if (!(caller eq cmRef)) return
		val (tbt, l2) = statusMap(code)
		if (isL2) statusMap.update(code, (tbt, target))
		else      statusMap.update(code, (target, l2))
	}
}
