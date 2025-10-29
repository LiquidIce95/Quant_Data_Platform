// File: src/main/scala/ClientIo.scala
package src.main.scala

import com.ib.client.EClientSocket
import java.util.concurrent.{LinkedBlockingQueue, Executors, TimeUnit}

final class ClientIo(c: EClientSocket) {
	private val q   = new LinkedBlockingQueue[Runnable]()
	private val io  = Executors.newSingleThreadExecutor()
	private val sch = Executors.newSingleThreadScheduledExecutor()

	io.submit(new Runnable { def run(): Unit = while (true) q.take().run() })

	def submit(f: EClientSocket => Unit): Unit =
		q.put(new Runnable { def run(): Unit = f(c) })

	def submitDelayed(ms: Long)(f: EClientSocket => Unit): Unit =
		sch.schedule(new Runnable { def run(): Unit = submit(f) }, ms, TimeUnit.MILLISECONDS)
}
