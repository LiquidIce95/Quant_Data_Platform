package src.main.scala
import scala.collection.mutable
import src.main.scala.Status.Online
import src.main.scala.TestOptimalDistributionFunction.fact


sealed abstract class Status
object Status {
	case object Online extends Status
	case object Offline extends Status
}

/**
  * also called highest random weight hashing
  *
  * @param api 
  */
abstract class RendevouzHasher {

    /**
      * maps every symbol's name to a sorted list (by score) of the enities names and the score's
      * the list is sorted descending by score
      */
    protected val scoreMap : mutable.Map[String,List[(String,Int)]] = mutable.Map.empty

    /**
      * map every entity name to a status,needs to be given 
      */
    val stateMap : mutable.Map[String,Status]

    /**
      * maps entity names to the list of symbols its taking care of
      */
    val entityMap : mutable.Map[String,List[String]] = mutable.Map.empty

    /**
      * we assume that the provided entityNames stay contant, so if you plan on use this for 
      * horizontal scaling, you provide the maximum entities, and all that are offline are treated the same way as if they 
      * were crashed
      * 
      */
    protected val entityNames : List[String]

    /**
      * all the names of all symbols we want to distribute
      *
      * 
      */
    protected val symbolNames : List[String]

   /**
      * computes the current entityMap based on scores and states,
      * should be used only for initialization since the apply function
      * updates states and entityMap on the fly
      */
    def initEntityMap():Unit={

      assert(entityMap.isEmpty)
      assert(scoreMap.nonEmpty)

      entityNames.foreach { e =>
        assert(stateMap.contains(e))
        entityMap.update(e, List.empty[String])
      }

      symbolNames.foreach { symbolName =>
        val scores = scoreMap(symbolName)
        assert(scores.nonEmpty)

        val ownerOpt =
          scores.find { case (e, _) => stateMap(e) == Status.Online }

        assert(ownerOpt.isDefined)
        val owner = ownerOpt.get._1

        val current = entityMap.getOrElse(owner, List.empty[String])
        entityMap.update(owner, current :+ symbolName)
      }
    }

    /**
      * asssigns to each symbol we want to shard a score
      * Usually people use it with an ideal hashfunction
      * but wee use a deterministic function with nice properties
      * that has garantees
      *
      * @param symbol the trading symbol we want to stream data for 
        @param entityName The name of some pod in the ib connector namespace or 
        broker keys in kafka, anythong we want to distribute symbols on
      * @return the score of that symbol and entityName
    */
    def scoreFunction(symbol:String,entityName:String):Int

    /**
      * popoulates the scoreMap with lists of the scores  per entity sorted by score descending
      * the keys are the symbolnames
      */
    def computeScores():Unit={
      symbolNames.foreach{
        symbolName : String => {
          var scoreList : List[(String,Int)] = List()
          entityNames.foreach{
            entityName:String => {
              val score = scoreFunction(symbolName,entityName)
              scoreList = scoreList.appended((entityName,score))
            }
          }
          scoreList = scoreList.sortBy(x=>(-1)*x._2)
          scoreMap.update(symbolName,scoreList)
        }
      }
    }

    /**
      * redistributes the symbols that got taken care of enittyName to the remaining 
      * enitties that are online
      * @param entityName the name of the entity which used to be online bu now is offline
      */
    def onEntityLost(entityName:String, onlineEntities:List[String]):Unit={

      assert(!onlineEntities.contains(entityName))

      val symbols = entityMap(entityName)
      entityMap.update(entityName, List.empty[String])

      symbols.foreach { symbolName =>
        val scores = scoreMap(symbolName)
        assert(scores.nonEmpty)

        val newOwnerOpt =
          scores.find { case (e, _) => onlineEntities.contains(e) }

        assert(newOwnerOpt.isDefined)
        val newOwner = newOwnerOpt.get._1

        val current = entityMap.getOrElse(newOwner, List.empty[String])
        entityMap.update(newOwner, current :+ symbolName)
      }
    }

    /**
      * reassignes the symbols which had highest score with this entity, to this entity
      *
      * @param entityName
      */
    def onEntityWon(entityName:String):Unit={

      if (!entityMap.contains(entityName)) {
        entityMap.update(entityName, List.empty[String])
      }

      symbolNames.foreach { symbolName =>
        val scores = scoreMap(symbolName)
        assert(scores.nonEmpty)

        val topEntity = scores.head._1
        if (topEntity == entityName) {
          entityMap.keys.foreach { e =>
            if (e != entityName) {
              val lst = entityMap.getOrElse(e, List.empty[String])
              val filtered = lst.filterNot(_ == symbolName)
              if (filtered ne lst) {
                entityMap.update(e, filtered)
              }
            }
          }

          val current = entityMap.getOrElse(entityName, List.empty[String])
          if (!current.contains(symbolName)) {
            entityMap.update(entityName, current :+ symbolName)
          }
        }
      }
    }

  /**
    * This implementation is not the most efficient one, but given that 
    * entities goind offline or online should be somewhat rare, especially multiple 
    * entities at once, its good enough
    * @param onlineEntities the list of entity names that are currenlty online
    * @return the updated version of entityMap
    */
  def apply(onlineEntities: List[String]):mutable.Map[String,List[String]]={
    assert(onlineEntities.forall(entityNames.contains))

    if (scoreMap.isEmpty) {
      computeScores()
    }

    if (entityMap.isEmpty) {
      initEntityMap()
    }

    entityNames.foreach {
      entityName:String => 
        stateMap(entityName) match {
          case Status.Online => {
            if (onlineEntities.contains(entityName)){
              //nothing to do, the entity was online and still is online
              ()
            }
            else {
              // the entity was online and now is offline we need to 
              // redistribute the sumbols of entityName to the enities that are online 
              // according to their highest scores
              onEntityLost(entityName,onlineEntities)
              stateMap.update(entityName,Status.Offline)
            }
          }
          case Status.Offline => {
            if (onlineEntities.contains(entityName)){
              // it was offline but now is online again, we need to assign all symbols to it
              // which have highest score with that entity
              onEntityWon(entityName)
              stateMap.update(entityName,Status.Online)
            }
            else {
              // was offline and still is offline, nothing to do
              ()
            }
          }
        }
    }


    entityMap
  }


}


object TestRendevouzHasher{


    def pickRandomIndex(n: Int, rnd: scala.util.Random): Int = {
        require(n > 0)
        rnd.nextInt(n)    // returns an integer in [0, n-1]
    }
    def pickRandomEntity(entities: List[String], rnd: scala.util.Random): String = {
        require(entities.nonEmpty)
        entities(rnd.nextInt(entities.size))
    }

    def onlineSymbolCounts(
        stateMap: mutable.Map[String, Status],
        entityMap: mutable.Map[String, List[String]]
    ): List[Int] = {
        stateMap.collect {
            case (entity, Status.Online) =>
                entityMap(entity).size
        }.toList
    }
  def testQuick():Unit= {
    for (n<-4 until 25){
        for (m<-2 until 800){
            val rnd = new scala.util.Random()

            val symbols = {for (j<-0 until m) yield j.toString()}.toList
            val entities = {for (i<-0 until n) yield i.toString()}.toList

            val scoreTable = new OptimalDistributionFunction(m,n)

            assert(scoreTable.thisMatrixFine(),"this TAble is not fine")
        
            val rendevouzH = new RendevouzHasher {
                protected val entityNames: List[String] = entities
                protected val symbolNames: List[String] = symbols 
                def scoreFunction(symbol: String, entityName: String): Int = {
                    val symbolInt = symbol.toInt
                    val entityInt = entityName.toInt
                    scoreTable.distributionMatrix(symbolInt)(entityInt)
                }
                val stateMap: mutable.Map[String,Status] = mutable.Map.from(for (e <- entities) yield e -> Status.Online)

            }

            val symbolsToEntities = rendevouzH(onlineEntities = entities)


            // here compute how many symbols each entity has , get min,max, assert(max-min<=1)
            val symbolCount = onlineSymbolCounts(rendevouzH.stateMap,symbolsToEntities)

            val min = symbolCount.min 
            val max = symbolCount.max 

            assert(max-min <=1,"min max comparison not good when all are online")
            // end of your solution

            val numberOfflineEnts = pickRandomIndex(n,rnd)

            var offlinePicks :List[String]= List.empty

            while(offlinePicks.size < numberOfflineEnts){
                //here create onlineEnts:List[String] that consists of all the current picks
                //then call rendevouzH(onlineEnts), 
                // perform the same check as above
                val offlinePick = pickRandomEntity(entities.filterNot(offlinePicks.contains),rnd)

                offlinePicks = offlinePicks :+ offlinePick
                val onlineEnts : List[String]=entities.filterNot(offlinePicks.contains)

                val updatedMap = rendevouzH(onlineEnts)

                val symbolCountNew = onlineSymbolCounts(rendevouzH.stateMap,updatedMap)

                val min = symbolCountNew.min 
                val max = symbolCountNew.max
                val ok    = max - min <= 1
                val red   = "\u001b[31m"
                val green = "\u001b[32m"
                val reset = "\u001b[0m"

                val color = if (ok) green else red

                //assert(max-min<=1,"min max comparison not good")
                println(
                  s"${color}assertion was $ok with max-min=${max - min} n:$n, m:$m offline size:${offlinePicks.size}$reset"
                )                // your implementation ends here
            }

        }


    }
  }

  def main(args: Array[String]):Unit={
    testQuick()
  }
}
