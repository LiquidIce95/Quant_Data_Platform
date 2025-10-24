// File: src/main/scala/ConnState.scala
package src.main.scala

object ConnState {
	sealed trait State
	case object INIT extends State
	case object VALID extends State
	case object INVALID extends State
}

/**
  * Simple holder for a connection state (INIT, VALID, INVALID).
  * No locks by design. Only the Connections singleton should mutate via unsafeSet.
  */
final class ConnState {
	private var connectionState: ConnState.State = ConnState.INIT

	// read-only for everyone
	def get: ConnState.State = connectionState

	// mutator reserved for Connections singleton
	private[src] def unsafeSet(s: ConnState.State): Unit = {
		connectionState = s
	}
}
