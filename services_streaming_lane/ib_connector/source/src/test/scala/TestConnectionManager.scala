package src.main.scala
import org.scalatest.funsuite.AnyFunSuite
import src.main.scala.ApiHandler
import sttp.client4.ws.SyncWebSocket
import src.main.scala.ConnectionManager
import scala.collection.mutable


class TestConnectionManager extends AnyFunSuite {
  test("basic test subscribing to one symbol") {

    //remember to also override the subscribe and unsubscribe methods for market depth data
    var countSubscribeCalls = 0
    var countUnsubscribeCalls = 0

    val mockedApi = new ApiHandler {
        def computeUser(): Int = 1
        override def computeSymbolUniverse(): Vector[(Long, String, String)] = {
            Vector((1,"1","1"),(2,"2","2"),(3,"3","3"))
        }
        override def subscribetbt(conId: String, webSocketOpt: Option[SyncWebSocket]): Unit = {
            countSubscribeCalls=countSubscribeCalls+1
            ()
        }
        override def unsubscribetbt(conId: String, webSocketOpt: Option[SyncWebSocket]): Unit = {
            countUnsubscribeCalls = countUnsubscribeCalls+1
            ()
        }
    }

    val connectionManager = new ConnectionManager(mockedApi) {
        def computeShards(): mutable.Map[String,Vector[(Long, String, String)]] = {
            mutable.Map("1"->Vector((1,"1","1")),"2"->Vector((2,"2","2")),"3"->Vector((3,"3","3")))
        }
        def determinePodIdentity(): String = "1"
    }

    val mockedWebSocket :SyncWebSocket = null.asInstanceOf[SyncWebSocket]

    connectionManager(mockedWebSocket)

    assert(countSubscribeCalls==1)
    assert(countUnsubscribeCalls==2)
  }

  test("changing shards"){
		var countSubscribeCalls: Int = 0
		var countUnsubscribeCalls: Int = 0
		var firstSharding: Boolean = true

		val mockedApi: ApiHandler = new ApiHandler {
			def computeUser(): Int = 1
			override def computeSymbolUniverse(): Vector[(Long, String, String)] = {
				Vector((1L, "1", "1"), (2L, "2", "2"), (3L, "3", "3"))
			}
			override def subscribetbt(conId: String, webSocketOpt: Option[SyncWebSocket]): Unit = {
				countSubscribeCalls = countSubscribeCalls + 1
				()
			}
			override def unsubscribetbt(conId: String, webSocketOpt: Option[SyncWebSocket]): Unit = {
				countUnsubscribeCalls = countUnsubscribeCalls + 1
				()
			}
		}

		val connectionManager: ConnectionManager = new ConnectionManager(mockedApi) {
			override def computeShards(): mutable.Map[String, Vector[(Long, String, String)]] = {
				if (firstSharding) {
					mutable.Map(
						"1" -> Vector((1L, "1", "1")),
						"2" -> Vector((2L, "2", "2"), (3L, "3", "3"))
					)
				} else {
					mutable.Map(
						"1" -> Vector((2L, "2", "2")),
						"2" -> Vector((1L, "1", "1"), (3L, "3", "3"))
					)
				}
			}
			override def determinePodIdentity(): String = "1"
		}

		val mockedWebSocket: SyncWebSocket = null.asInstanceOf[SyncWebSocket]

		connectionManager(mockedWebSocket)
		firstSharding = false
		connectionManager(mockedWebSocket)

		assert(countSubscribeCalls == 2)
		assert(countUnsubscribeCalls == 4)
	}    

}