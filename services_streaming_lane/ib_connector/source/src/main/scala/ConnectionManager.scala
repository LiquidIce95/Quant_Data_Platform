package src.main.scala

import sttp.client4.ws.SyncWebSocket
import sttp.client4.quick._

trait ConnectionManager {

    /**
      * compute shards overwrites this, represents the 
      * shards for which each pod is resonsible for 
      * a symbol is a tuple wher the first component is the conId, the second is expiry date
      * 
      */
    var symbolShards: Map[String,List[(Long,String)]]= Map.empty

    /**
      * Long is the contract Id from interactive brokers
      * String is the expiry date
      * a symbol is a tuple wher the first component is the conId, the second is expiry date
      * 
      */
    var symbolUniverse:List[(Long,String)]=Nil

    var podIdentity: String = "" 

    /**
      * is needed for subscribing / unsubscribing to L2 data
      *
      * 
      */
    var accId:String =""

    /**
      * The SmdProcessor will push / append requests here if a connection state is faulty
      * and its the only entity that can do that and ConnectionManager the only entity 
      * that can pop request from the set
      * will contain conId to resubscribe the smd topic
      * 
      */
    var requestSetSmd :Set[Long]= Set()

    /**
      * The SbdProcessor will push / append requests here if a connection state is faulty
      * and its the only entity that can do that, and ConnectionManager the only entity 
      * that can pop request from the set
      * will contain conId to resubscribe the sbd topic
      * 
      */
    var requestSetSbd: Set[Long]=Set()
	/**
	  * has no arguments because for testing we provide a constant function and 
	  * for produciton the function must use kubernets api to get peers the function
	  * that performs the sharding is tested seperately
	  * pod identity and peer identities are assumed to be constant during runtime
	  * @return a map that relates the peer names to the list of symbols / shard it must process
      * where the symbols are tuples, where the first component is conId and the second is expiry date
	  */
	def computeShards(): Map[String, List[(Long,String)]]


    /**
      * returns the identity of this pod that is consistent with the pod identities of 
      * its peers that will be used as keys by the computeShards function
      * needs to assert if podIdenitty is in symbolShars as a key!
      * @return
      */
    def determinePodIdentity():String

    /**
      * calls and endpoint to get accountId and stores the accountId in this attribute
      *
      * @return
      */
    def getAccountId():String

    /**
      * First we need to read all requests from the requestSet without popping / deleting them. Then we 
      * send unsubscribe and subscribe messages over the ApiHandler to the socket, wait a little bit for the reader to receive the 
      * initailizing frame that will bring the connection states to a valid state, only then we pop the fullfilled requests
      */
    def apply(ws:SyncWebSocket):Unit={
        if (symbolUniverse==Nil){
            // CL
            val clReq   = ApiHandler.endpointsMap(EndPoints.FuturesContractCL)
            val clResp  = clReq.send()
            val clBody  = clResp.body
            val clJson  = ujson.read(clBody)
            val clArray = clJson("CL").arr

            val clFront5: List[(Long, String)] =
                clArray
                    .sortBy(c => c("expirationDate").num.toLong)
                    .take(5)
                    .map { c =>
                        val conId = c("conid").num.toLong
                        val exp   = c("expirationDate").num.toLong.toString
                        (conId, exp)
                    }
                    .toList

            // NG
            val ngReq   = ApiHandler.endpointsMap(EndPoints.FuturesContractNG)
            val ngResp  = ngReq.send()
            val ngBody  = ngResp.body
            val ngJson  = ujson.read(ngBody)
            val ngArray = ngJson("NG").arr

            val ngFront5: List[(Long, String)] =
                ngArray
                    .sortBy(c => c("expirationDate").num.toLong)
                    .take(5)
                    .map { c =>
                        val conId = c("conid").num.toLong
                        val exp   = c("expirationDate").num.toLong.toString
                        (conId, exp)
                    }
                    .toList

            symbolUniverse = clFront5 ++ ngFront5
        }

        symbolShards = computeShards()

        if(podIdentity==""){
            podIdentity=determinePodIdentity()
        }

        if(accId==""){
            accId=getAccountId()
        }

        // Now we first unsubscribe from all conId in the requestSetSmd and requestSetSbd , wait 100 ms , then subscribe them again
        // then we wait 400 milliseconds, then we  pop / remove all the processed requests form requestSet

        // next for every symbol (Long,String) not in the shard for this pod (symbolShards[podIdentity]) we unsubscribe smt and sbd
        // for every symbol that is in the shard we subscribe
      	// --- Handle explicit re-subscribe requests (read without popping first) ---
		val toResubSmd: Set[Long] = requestSetSmd
		val toResubSbd: Set[Long] = requestSetSbd

		// SMD (tick-by-tick)
		toResubSmd.foreach { con =>
			val c = con.toString
			ApiHandler.unsubscribetbt(c, Some(ws))
		}
		Thread.sleep(100L)
		toResubSmd.foreach { con =>
			val c = con.toString
			ApiHandler.subscribetbt(c, Some(ws))
		}

		// SBD (L2 DOM)
		toResubSbd.foreach { con =>
			val c = con.toString
			ApiHandler.unsubscribeL2(accId, c, Some(ws))
		}
		Thread.sleep(100L)
		toResubSbd.foreach { con =>
			val c = con.toString
			ApiHandler.subscribeL2(accId, c, Some(ws))
		}

		// Wait for initializing frames to arrive; then pop fulfilled requests
		Thread.sleep(400L)
		requestSetSmd = requestSetSmd -- toResubSmd
		requestSetSbd = requestSetSbd -- toResubSbd

		// --- Enforce shard-based subscriptions for all symbols ---
		val myShardConIds: Set[Long] = symbolShards.getOrElse(podIdentity, Nil).map(_._1).toSet
		val universeConIds: Set[Long] = symbolUniverse.map(_._1).toSet

		// Unsubscribe everything not in my shard
		(universeConIds -- myShardConIds).foreach { con =>
			val c = con.toString
			ApiHandler.unsubscribetbt(c, Some(ws))
			ApiHandler.unsubscribeL2(accId, c, Some(ws))
		}

		// Subscribe everything in my shard
		myShardConIds.foreach { con =>
			val c = con.toString
			ApiHandler.subscribetbt(c, Some(ws))
			ApiHandler.subscribeL2(accId, c, Some(ws))
		}
    }
}