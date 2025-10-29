// File: src/main/scala/Connections.scala
package src.main.scala

import scala.collection.mutable

/** 
  * Two-state connection state model. 
  * 
  * Defines the minimal set of states a connection leg can have: 
  * either `VALID` (active and processing) or `INVALID` (inactive or canceled). 
  */
object ConnState {
	sealed trait State
	case object VALID extends State
	case object INVALID extends State
}

/**
  * Thread-safe single connection state holder.
  *
  * Each connection leg (Tick-by-Tick or L2) uses an instance of this class.
  * State visibility is ensured using `@volatile`, meaning readers always see
  * the latest written value without synchronization.
  *
  * @note State changes are controlled externally through the `Connections` singleton.
  */
final class ConnState {
	@volatile private var connectionState: ConnState.State = ConnState.INVALID

	/** 
	  * Returns the current state (`VALID` or `INVALID`).
	  * @return current connection state
	  */
	def get: ConnState.State = connectionState

	/**
	  * Updates the connection state without synchronization.
	  * 
	  * @param s new state to set
	  * @note This method is intentionally package-private (`unsafeSet`) 
	  *       and should only be called from the `Connections` singleton.
	  */
	private[src] def unsafeSet(s: ConnState.State): Unit = { connectionState = s }
}

/**
  * Centralized singleton that manages all connection states, statuses,
  * and discovery mappings.
  *
  * This object defines who (manager or wrapper) may perform which mutations,
  * ensures thread-safety via synchronization, and maintains consistent 
  * connection lifecycle semantics.
  *
  * ## State machine (no INIT phase)
  * - Default state: `INVALID`
  * - Transition rules:
  *   - `VALID ← (manager only) — INVALID`
  *   - `INVALID ← (wrapper only) — VALID`
  *   - Same → same transitions are idempotent
  *   - Other transitions are ignored
  *
  * ## Status model
  * - Status values: `ON` / `DROPPED`
  * - Default status: `DROPPED`
  * - Status may be changed only by the manager.
  *
  * @throws IllegalStateException if an unknown code is mutated or queried
  */
object Connections {

	/** 
	  * Status of a connection leg: either active (`ON`) or stopped (`DROPPED`). 
	  */
	sealed trait ConnStatus
	case object ON extends ConnStatus
	case object DROPPED extends ConnStatus

	// Lookup and state tables
	private val lookupMap: mutable.Map[Int, String] = mutable.HashMap.empty
	private val stateMap:	mutable.Map[String,(ConnState, ConnState)] = mutable.HashMap.empty
	private val statusMap: mutable.Map[String,(ConnStatus, ConnStatus)] = mutable.HashMap.empty

	// Authorized actors
	@volatile private var ewRef: EwrapperImplementation = null
	@volatile private var cmRef: ConnManager = null

	// -------- lifecycle -----------------------------------------------------

	/**
	  * Fully resets the singleton.
	  *
	  * Clears all internal tables and removes registered actor references.
	  * 
	  * @note Should be called before initialization or in test setups.
	  */
	def reset(): Unit = this.synchronized {
		lookupMap.clear(); stateMap.clear(); statusMap.clear()
		ewRef = null; cmRef = null
	}

	/**
	  * Registers authorized actors.
	  *
	  * Only the registered `EwrapperImplementation` (wrapper) and 
	  * `ConnManager` (manager) are allowed to mutate states or statuses.
	  *
	  * @param ew reference to the wrapper instance
	  * @param cm reference to the connection manager instance
	  */
	def setActors(ew: EwrapperImplementation, cm: ConnManager): Unit = this.synchronized {
		ewRef = ew; cmRef = cm
	}

	// -------- discovery helpers --------------------------------------------

	/**
	  * Adds or updates a request ID → symbol lookup mapping.
	  *
	  * @param reqId IB request ID
	  * @param code  trading symbol (e.g., "CLZ4")
	  */
	def putLookup(reqId: Int, code: String): Unit = this.synchronized {
		lookupMap.update(reqId, code)
	}

	/**
	  * Ensures that a new symbol entry exists in both state and status maps.
	  * 
	  * Initializes both legs (Tick-by-Tick and L2) to `INVALID` and `DROPPED`.
	  *
	  * @param code trading symbol code
	  */
	def ensureEntry(code: String): Unit = this.synchronized {
		if (!stateMap.contains(code)) stateMap.update(code, (new ConnState, new ConnState))
		if (!statusMap.contains(code)) statusMap.update(code, (DROPPED, DROPPED))
	}

	// -------- reads ---------------------------------------------------------

	/** 
	  * Returns the list of all known trading symbol codes.
	  */
	def codes: Iterable[String] = this.synchronized { stateMap.keys.toList }

	/** 
	  * Returns the mapping (reqId, code) sorted by reqId.
	  */
	def entriesSortedByReqId: Seq[(Int,String)] = this.synchronized {
		lookupMap.toSeq.sortBy(_._1)
	}

	/**
	  * Retrieves the current connection state for the given code and leg.
	  *
	  * @param code trading symbol
	  * @param isL2 true for L2 leg, false for Tick-by-Tick
	  * @return current state (`VALID` or `INVALID`)
	  * @throws IllegalStateException if the code is unknown
	  */
	def stateOf(code: String, isL2: Boolean): ConnState.State = this.synchronized {
		requireKnown(code)
		val (tbt,l2) = stateMap(code)
		if (isL2) l2.get else tbt.get
	}

	/**
	  * Retrieves the current connection status for the given code and leg.
	  *
	  * @param code trading symbol
	  * @param isL2 true for L2 leg, false for Tick-by-Tick
	  * @return current status (`ON` or `DROPPED`)
	  * @throws IllegalStateException if the code is unknown
	  */
	def statusOf(code: String, isL2: Boolean): ConnStatus = this.synchronized {
		requireKnown(code)
		val (tbt,l2) = statusMap(code)
		if (isL2) l2 else tbt
	}

	/**
	  * Returns the trading symbol for a given request ID.
	  *
	  * @param reqId IB request ID
	  * @return trading symbol or "?" if unknown
	  */
	def lookupFor(reqId: Int): String = this.synchronized {
		lookupMap.getOrElse(reqId, "?")
	}

	/**
	  * Indicates whether discovery mappings are initialized.
	  *
	  * @return true if any of the lookup/state/status maps are empty
	  */
	def discoveryEmpty: Boolean = this.synchronized {
		lookupMap.isEmpty || stateMap.isEmpty || statusMap.isEmpty
	}

	// -------- writes (guarded + validated) ---------------------------------

	/**
	  * Ensures that a symbol is known before mutation.
	  *
	  * @param code trading symbol
	  * @throws IllegalStateException if code is missing from internal maps
	  */
	private def requireKnown(code: String): Unit = {
		if (!stateMap.contains(code) || !statusMap.contains(code))
			throw new IllegalStateException(s"Connections: unknown code=$code")
	}

	/**
	  * Attempts a state transition following the access rules.
	  *
	  * @param caller reference to the calling object (must be wrapper or manager)
	  * @param code trading symbol
	  * @param isL2 true for L2 leg, false for Tick-by-Tick
	  * @param target desired target state
	  *
	  * Rules:
	  *   - Manager may transition `INVALID -> VALID`
	  *   - Wrapper may transition `VALID -> INVALID`
	  *   - Same → same is idempotent
	  *   - All other transitions are ignored
	  *
	  * @throws IllegalStateException if the symbol is unknown
	  */
	def setState(caller: AnyRef, code: String, isL2: Boolean, target: ConnState.State): Unit = this.synchronized {
		requireKnown(code)
		val isMgr = caller eq cmRef
		val isEw	= caller eq ewRef
		if (!isMgr && !isEw) return

		val (tbt, l2) = stateMap(code)
		val cs	= if (isL2) l2 else tbt
		val cur = cs.get

		val legal = (cur, target) match {
			case (x, y) if x == y => true // idempotent
			case (ConnState.INVALID, ConnState.VALID) => isMgr
			case (ConnState.VALID, ConnState.INVALID) => isEw
			case _ => false
		}
		if (legal) cs.unsafeSet(target)
	}

	/**
	  * Updates the connection status (ON/DROPPED).
	  *
	  * Only the manager may perform this mutation; all other callers are ignored.
	  *
	  * @param caller reference to the calling object
	  * @param code trading symbol
	  * @param isL2 true for L2 leg, false for Tick-by-Tick
	  * @param target desired status
	  * @throws IllegalStateException if the symbol is unknown
	  */
	def setStatus(caller: AnyRef, code: String, isL2: Boolean, target: ConnStatus): Unit = this.synchronized {
		requireKnown(code)
		if (!(caller eq cmRef)) return
		val (tbt, l2) = statusMap(code)
		if (isL2) statusMap.update(code, (tbt, target))
		else			statusMap.update(code, (target, l2))
	}
}
