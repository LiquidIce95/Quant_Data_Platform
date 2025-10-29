// File: src/main/scala/ClientIo.scala
package src.main.scala

import com.ib.client.EClientSocket
import java.util.concurrent.{LinkedBlockingQueue, Executors, TimeUnit}

/**
  * Single-threaded I/O executor for IB's EClientSocket.
  *
  * This helper ensures that all invocations targeting the underlying socket are
  * serialized (FIFO) on a dedicated thread, avoiding reentrancy and concurrency
  * issues with the IB Java client. A separate scheduled executor is used to post
  * delayed submissions without blocking the I/O thread.
  *
  * Usage:
  *   - Use [[submit]] to enqueue an immediate socket operation.
  *   - Use [[submitDelayed]] to enqueue a socket operation after a delay.
  *
  * @param c the already-initialized EClientSocket that all submitted functions will receive
  * @note The internal I/O thread loops forever; callers are expected to manage process lifecycle.
  */
final class ClientIo(c: EClientSocket) {
	private val q   = new LinkedBlockingQueue[Runnable]()
	private val io  = Executors.newSingleThreadExecutor()
	private val sch = Executors.newSingleThreadScheduledExecutor()

	io.submit(new Runnable { def run(): Unit = while (true) q.take().run() })

	/**
	  * Enqueue a socket operation to be executed on the single I/O thread.
	  *
	  * The provided function is invoked with the shared [[EClientSocket]] instance.
	  * Calls are executed in the exact order they are submitted.
	  *
	  * @param f a function that performs an operation against the given EClientSocket
	  */
	def submit(f: EClientSocket => Unit): Unit =
		q.put(new Runnable { def run(): Unit = f(c) })

	/**
	  * Enqueue a socket operation to be executed after a specified delay.
	  *
	  * The operation is scheduled on a separate scheduler and, when due, is
	  * forwarded to [[submit]] so it still runs on the single I/O thread in FIFO order.
	  *
	  * @param ms delay in milliseconds before the operation is enqueued for execution
	  * @param f  a function that performs an operation against the given EClientSocket
	  */
	def submitDelayed(ms: Long)(f: EClientSocket => Unit): Unit =
		sch.schedule(new Runnable { def run(): Unit = submit(f) }, ms, TimeUnit.MILLISECONDS)
}
