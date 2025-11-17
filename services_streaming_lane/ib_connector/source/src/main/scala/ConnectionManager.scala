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
          symbolUniverse=ApiHandler.computeSymbolUniverse()
      }

      symbolShards = computeShards()

      if(podIdentity==""){
          podIdentity=determinePodIdentity()
      }

      if(accId==""){
          accId=getAccountId()
      }

      // --- Enforce shard-based subscriptions for all symbols ---
      val myShardConIds: Set[Long] = symbolShards.getOrElse(podIdentity, Nil).map(_._1).toSet
      val universeConIds: Set[Long] = symbolUniverse.map(_._1).toSet

      // Unsubscribe everything not in my shard
      (universeConIds -- myShardConIds).foreach { con =>
        val c = con.toString
        ApiHandler.unsubscribetbt(c, Some(ws))
        //ApiHandler.unsubscribeL2(accId, c, Some(ws))
      }

      // Subscribe everything in my shard
      myShardConIds.foreach { con =>
        val c = con.toString
        ApiHandler.subscribetbt(c, Some(ws))
        //ApiHandler.subscribeL2(accId, c, Some(ws))
      }
    }
}