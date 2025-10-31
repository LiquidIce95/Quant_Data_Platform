package src.main.scala.Boilerplate

import sttp.client4.quick._
import sttp.client4.Response
import sttp.client4.quick


object play {
	def main(args: Array[String]): Unit = {
		val response: Response[String] = quickRequest
		  	.header("Host", "localhost")
			.get(uri"https://client-portal.client-portal-api.svc:5000/v1/api/iserver/auth/status")
			.send()

		println(response.code)
		println(response.body)
	}
}
