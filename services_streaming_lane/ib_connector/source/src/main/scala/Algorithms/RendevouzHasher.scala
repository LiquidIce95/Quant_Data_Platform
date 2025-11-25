package src.main.scala
import scala.util.hashing.MurmurHash3

import scala.collection.mutable
import src.main.scala.Status.Online
import org.apache.commons.math3.util.CombinatoricsUtils
import scala.util.control.Breaks

sealed abstract class Status
object Status {
	case object Online extends Status
	case object Offline extends Status
}

/**
  * also called highest random weight hashing
  *
  * 
  */
abstract class RendevouzHasher {

	/**
	  * maps every symbol's name to a sorted list (by score) of the enities names and the score's
	  * the list is sorted descending by score
	  */
	//protected val scoreMap : mutable.Map[String,Vector[(String,Int)]] = mutable.Map.empty

	/**
	  * map every entity name to a status,needs to be given 
	  */
	val stateMap: mutable.Map[String, Status]

	/**
	  * maps entity names to the list of symbols its taking care of
	  */
	val entityMap: mutable.Map[String, Vector[String]] = mutable.Map.empty

	/**
	  * we assume that the provided entityNames stay contant, so if you plan on use this for 
	  * horizontal scaling, you provide the maximum entities, and all that are offline are treated the same way as if they 
	  * were crashed
	  * 
	  */
	protected val entityNames: Vector[String]

	/**
	  * all the names of all symbols we want to distribute
	  *
	  * 
	  */
	protected val symbolNames: Vector[String]

	/**
	  * for testing reasons 
	  */
	def name :String = "RendevouzHasher"

	/**
	  * computes the current entityMap based on scores and states,
	  * should be used only for initialization since the apply function
	  * updates states and entityMap on the fly
	  */
	def initEntityMap(): Unit = {

		assert(entityMap.isEmpty)

		entityNames.foreach { e =>
			assert(stateMap.contains(e))
			entityMap.update(e, Vector.empty[String])
		}

		symbolNames.foreach { symbolName =>
			val scores = {
				for (entName <- entityNames)
					yield (entName, scoreFunction(symbolName, entName))
			}.toVector.sortBy(x => (-1) * x._2)
			assert(scores.nonEmpty)

			val ownerOpt =
				scores.find { case (e, _) => stateMap(e) == Status.Online }

			assert(ownerOpt.isDefined)
			val owner = ownerOpt.get._1

			val current = entityMap.getOrElse(owner, Vector.empty[String])
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
	  *   @param entityName The name of some pod in the ib connector namespace or 
	  *   broker keys in kafka, anythong we want to distribute symbols on
	  * @return the score of that symbol and entityName
	  */
	def scoreFunction(symbol: String, entityName: String): Int


	/**
	  * redistributes the symbols that got taken care of enittyName to the remaining 
	  * enitties that are online
	  * @param entityName the name of the entity which used to be online bu now is offline
	  */
	def onEntityLost(entityName: String, onlineEntities: Vector[String]): Unit = {

		assert(!onlineEntities.contains(entityName))

		val symbols = entityMap(entityName)
		entityMap.update(entityName, Vector.empty[String])

		symbols.foreach { symbolName =>
			val scores = {
				for (entName <- onlineEntities)
					yield (entName, scoreFunction(symbolName, entName))
			}.toVector.sortBy(x => (-1) * x._2)

			assert(scores.nonEmpty)

			val newOwnerOpt =
				scores.find { case (e, _) => onlineEntities.contains(e) }

			assert(newOwnerOpt.isDefined)
			val newOwner = newOwnerOpt.get._1

			val current = entityMap(newOwner)
			entityMap.update(newOwner, current :+ symbolName)
		}
	}

	/**
	  * reassignes the symbols which had highest score with this entity, to this entity
	  *
	  * @param entityName
	  */
	def onEntityWon(entityName: String): Unit = {

		if (!entityMap.contains(entityName)) {
			entityMap.update(entityName, Vector.empty[String])
		}

		symbolNames.foreach { symbolName =>
			val scores = {
				for (entName <- entityNames)
					yield (entName, scoreFunction(symbolName, entName))
			}.toVector.sortBy(x => (-1) * x._2)
			assert(scores.nonEmpty)

			val topEntity = scores.head._1
			if (topEntity == entityName) {
				entityMap.keys.foreach { e =>
					if (e != entityName) {
						val lst = entityMap.getOrElse(e, Vector.empty[String])
						val filtered = lst.filterNot(_ == symbolName)
						if (filtered ne lst) {
							entityMap.update(e, filtered)
						}
					}
				}

				val current = entityMap.getOrElse(entityName, Vector.empty[String])
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
	def apply(onlineEntities: Vector[String]): mutable.Map[String, Vector[String]] = {
		assert(onlineEntities.forall(entityNames.contains))

		if (entityMap.isEmpty) {
			initEntityMap()
		}
		val offlineEntities = {
			for (ent <- entityNames if !onlineEntities.contains(ent))
				yield ent.toInt
		}.toVector
		entityNames.foreach {
			entityName: String =>
				stateMap(entityName) match {
					case Status.Online =>
						if (onlineEntities.contains(entityName)) {
							()
						} else {
							onEntityLost(entityName,onlineEntities)
							stateMap.update(entityName, Status.Offline)
						}
					case Status.Offline =>
						if (onlineEntities.contains(entityName)) {
							onEntityWon(entityName)
							stateMap.update(entityName, Status.Online)
						} else {
							()
						}
				}
		}

		entityMap
	}
}

object PerformanceComparison {

	def pickRandomIndex(n: Int, rnd: scala.util.Random): Int = {
		require(n > 0)
		rnd.nextInt(n)
	}

	def pickRandomEntity(entities: Vector[String], rnd: scala.util.Random): String = {
		require(entities.nonEmpty)
		entities(rnd.nextInt(entities.size))
	}

	def onlineSymbolCounts(
		stateMap: mutable.Map[String, Status],
		entityMap: mutable.Map[String, Vector[String]]
	): Vector[Int] = {
		stateMap.collect {
			case (entity, Status.Online) =>
				entityMap(entity).size
		}.toVector
	}

	def main(args : Array[String]): Unit = {
		val results : mutable.Map[String,(Int,Float)] = mutable.Map.empty
		for (n <- 2 until 20) {
			for (m <- 3*n until 15*n by n) {
				val symbols = {for (j <- 0 until m) yield j.toString()}.toVector
				val entities = {for (i <- 0 until n) yield i.toString()}.toVector

				val scoreTable = new OptimalDistributionFunction(m, n)
				assert(scoreTable.thisMatrixFine(), "this Table is not fine")


				for (l <-0 until 500) {
										
					// add the implemenations here
					val rendevouzScoreTable = new RendevouzHasher {
						protected val entityNames: Vector[String] = entities
						protected val symbolNames: Vector[String] = symbols
						def scoreFunction(symbol: String, entityName: String): Int = {
							val symbolInt = symbol.toInt
							val entityInt = entityName.toInt

							scoreTable.distributionMatrix(symbolInt)(entityInt)
						}
						val stateMap: mutable.Map[String, Status] =
							mutable.Map.from(for (e <- entities) yield e -> Status.Online)

						override def name: String = "ScoreTableVariant"
					}

					val rendevouzMurmurHash = new RendevouzHasher {
						protected val entityNames: Vector[String] = entities
						protected val symbolNames: Vector[String] = symbols
						def scoreFunction(symbol: String, entityName: String): Int = {
							val h = MurmurHash3.stringHash(symbol + "|" + entityName)

							if (h == Int.MinValue) 0 else math.abs(h)
						}
						val stateMap: mutable.Map[String, Status] =
							mutable.Map.from(for (e <- entities) yield e -> Status.Online)

						override def name: String = "MurmurHashVariant"
					}

					//too lazy to create a new abstract class
					val rendevouzDynamic = new RendevouzHasher {
						protected val entityNames: Vector[String] = entities
						protected val symbolNames: Vector[String] = symbols
						//we wont need this
						def scoreFunction(symbol: String, entityName: String): Int = {
							-1
						}
						val stateMap: mutable.Map[String, Status] =
							mutable.Map.from(for (e <- entities) yield e -> Status.Online)

						/**
						  * redistributes the symbols that got taken care of enittyName to the remaining 
						  * enitties that are online
						  * @param entityName the name of the entity which used to be online bu now is offline
						  */
						override def onEntityLost(entityName: String, onlineEntities: Vector[String]): Unit = {

							assert(!onlineEntities.contains(entityName))

							val symbols = entityMap(entityName)
							entityMap.update(entityName, Vector.empty[String])

							val onlineEntsSorted =
								{ for (onlineEnt <- onlineEntities) yield (onlineEnt, entityMap(onlineEnt).size) }
									.toVector
									.sortBy(x => x._2)
							var index = 0
							symbols.foreach { symbolName =>

								val newOwner = onlineEntsSorted(index)._1

								val current = entityMap(newOwner)
								entityMap.update(newOwner, current :+ symbolName)
								index = (index + 1) % onlineEntsSorted.size
							}
						}

						override def initEntityMap(): Unit = {
							assert(entityMap.isEmpty)

							entityNames.foreach { e =>
								assert(stateMap.contains(e))
								entityMap.update(e, Vector.empty[String])
							}
							var index = 0
							symbolNames.foreach { symbolName =>

								val owner = entityNames(index)

								val current = entityMap.getOrElse(owner, Vector.empty[String])
								entityMap.update(owner, current :+ symbolName)
								index = (index + 1) % entityNames.size
							}
						}

						// we need to also override OnEntityWon but for these tests its not necessary, later we use the same idea

						override def name: String = "DynamicVariant"
					}

					
					val rendevouzVariants: Vector[RendevouzHasher] = Vector(rendevouzScoreTable,rendevouzMurmurHash,rendevouzDynamic)


					for (variant <- rendevouzVariants) {
						
						val rnd = new scala.util.Random()
						val symbolsToEntities = variant(onlineEntities = entities)

						val symbolCount = onlineSymbolCounts(variant.stateMap, symbolsToEntities)

						val min = symbolCount.min
						val max = symbolCount.max

						var worstPercentage = max.toFloat / min.toFloat - 1.0f
						var maxDiff = max-min

						if (!results.contains(variant.name)){
							results(variant.name)=(maxDiff,worstPercentage)
						} 



						val numberOfflineEnts = pickRandomIndex(n, rnd)

						var offlinePicks: Vector[String] = Vector.empty

						while (offlinePicks.size < numberOfflineEnts) {
							val offlinePick = pickRandomEntity(entities.filterNot(offlinePicks.contains), rnd)

							offlinePicks = offlinePicks :+ offlinePick
							val onlineEnts: Vector[String] = entities.filterNot(offlinePicks.contains)

							val updatedMap = variant(onlineEnts)

							val symbolCountNew = onlineSymbolCounts(variant.stateMap, updatedMap)

							// this is critical behaviour
							assert(symbolCountNew.sum == m)

							val min = symbolCountNew.min
							val max = symbolCountNew.max
							

							if (min != 0 && worstPercentage < max.toFloat / min.toFloat - 1.0f) {
								worstPercentage = max.toFloat / min.toFloat - 1.0f								
							}
							if (min == 0) {
								worstPercentage = 1000.0f
							}
							if (maxDiff < (max - min)) maxDiff = max - min
							
							var curr = results(variant.name)

							if (curr._1<maxDiff) (results(variant.name) = (maxDiff,curr._2))

							curr = results(variant.name)
							if (curr._2<worstPercentage) (results(variant.name)= (curr._1,worstPercentage))



						}

					}
				
				}
				
			}

		}
		for (entry <- results ){
			println(f"for variant: ${entry._1} ,the worst percentage was ${100.0f * entry._2._2} and worst max diff ${entry._2._1}")
		}
	}

}
