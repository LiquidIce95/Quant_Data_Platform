package src.main.scala

object ConnState {
	sealed trait State
	case object INIT extends State
	case object VALID extends State
	case object INVALID extends State
	case object DROPPED extends State
}

final class ConnState {
	import ConnState._

	// Single shared object, two threads (manager & EReader/Ewrapper).
	// Use ThreadLocal to tag the caller per-thread without locks.
	private val callerCtx = new ThreadLocal[Byte]() {
		override def initialValue(): Byte = 0
	}
	private final val CALLER_NONE: Byte = 0
	private final val CALLER_MANAGER: Byte = 1
	private final val CALLER_EWRAPPER: Byte = 2

	private var connectionState: State = INIT

	def asManager: ConnState = {
		callerCtx.set(CALLER_MANAGER)
		this
	}

	def asEwrapper: ConnState = {
		callerCtx.set(CALLER_EWRAPPER)
		this
	}

	// Binary operator to request a transition; caller is read from ThreadLocal.
	def ->(target: State): Unit = {
		val who = callerCtx.get()
		if (who == CALLER_MANAGER) {
			// Manager rules:
			// INIT -> VALID
			if (connectionState == INIT && target == VALID) {
				connectionState = VALID
			}
			// VALID -> DROPPED
			else if (connectionState == VALID && target == DROPPED) {
				connectionState = DROPPED
			}
			// VALID -> INVALID (allowed for manager too)
			else if (connectionState == VALID && target == INVALID) {
				connectionState = INVALID
			}
		} else if (who == CALLER_EWRAPPER) {
			// Ewrapper rules:
			// VALID -> INVALID
			if (connectionState == VALID && target == INVALID) {
				connectionState = INVALID
			}
			// DROPPED -> INVALID
			else if (connectionState == DROPPED && target == INVALID) {
				connectionState = INVALID
			}
		}
		// Clear only this thread's context
		callerCtx.set(CALLER_NONE)
	}

	// Optional: minimal getter (no sugar)
	def getState: State = connectionState
}
