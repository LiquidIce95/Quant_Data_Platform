package src.main.scala

import scala.collection.mutable

/**
  * simple sharding algorithm, literally distributes the symbols as evenly as possible 
  * on all entities that reamin online
  * @param entityNames of type E, represents all of the entities and is fixed so if you intend to updscale, you need to decide what
  * the maximum number of entities will be then the algorithm will downscale to the initial constellation
  * @param symbolNames of type S, all symbols also fixed during runtime
  */
class RoundRobin[E,S](
	val entityNames: Vector[E],
	val symbolNames: Vector[S]
) {

	val stateMap: mutable.Map[E, Status] =
		mutable.Map.from(for (e <- entityNames) yield e -> Status.Online)

	val entityMap: mutable.Map[E, Vector[S]] =
		mutable.Map.empty

	def initEntityMap(): Unit = {
		assert(entityMap.isEmpty)

		entityNames.foreach { e =>
			assert(stateMap.contains(e))
			entityMap.update(e, Vector.empty[S])
		}
		var index: Int = 0
		symbolNames.foreach { symbolName =>
			val owner: E = entityNames(index)
			val current: Vector[S] = entityMap.getOrElse(owner, Vector.empty[S])
			entityMap.update(owner, current :+ symbolName)
			index = (index + 1) % entityNames.size
		}
	}

	def onEntityLost(entityName: E, onlineEntities: Vector[E]): Unit = {
		assert(!onlineEntities.contains(entityName))

		val symbols: Vector[S] = entityMap(entityName)
		entityMap.update(entityName, Vector.empty[S])

		val onlineEntsSorted: Vector[(E, Int)] =
			(for (onlineEnt <- onlineEntities) yield (onlineEnt, entityMap(onlineEnt).size))
				.toVector
				.sortBy(x => x._2)

		var index: Int = 0
		symbols.foreach { symbolName =>
			val newOwner: E = onlineEntsSorted(index)._1
			val current: Vector[S] = entityMap(newOwner)
			entityMap.update(newOwner, current :+ symbolName)
			index = (index + 1) % onlineEntsSorted.size
		}
	}

	def onEntityWon(entityName:E):Unit={
		val onlineEntities = {for (x<- entityNames if stateMap(x)==Status.Online) yield (x,entityMap(x).size)}.toVector.sortBy(x=>(-1)*x._2)
		val offlineEntities = {for (x<- entityNames if stateMap(x)==Status.Offline) yield x}.toVector

		assert(offlineEntities.contains(entityName))

		var index :Int = 0
		val atLeast = math.floor(symbolNames.size/(onlineEntities.size+1)).toInt
		var newSymbols = Vector.empty[S]
		for (_<-0 until atLeast){
			val currEnt = onlineEntities(index)._1
			var entSymbols = entityMap(currEnt)
			val last = entSymbols.last

			newSymbols = newSymbols ++ Vector(last)
			entityMap(currEnt) = entityMap(currEnt).dropRight(1)
			index = (index+1)%onlineEntities.size
		}
		entityMap(entityName)=newSymbols


	}

	def apply(onlineEntities: Vector[E]): mutable.Map[E, Vector[S]] = {
		assert(onlineEntities.forall(entityNames.contains))

		if (entityMap.isEmpty) {
			initEntityMap()
		}

		entityNames.foreach { entityName: E =>
			stateMap(entityName) match {
				case Status.Online =>
					if (onlineEntities.contains(entityName)) {
						()
					} else {
						onEntityLost(entityName, onlineEntities)
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
