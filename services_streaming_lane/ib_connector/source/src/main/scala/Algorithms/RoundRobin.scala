package src.main.scala

import scala.collection.mutable

class RoundRobin(
	val entityNames: Vector[String],
	val symbolNames: Vector[String]
) {

	val stateMap: mutable.Map[String, Status] =
		mutable.Map.from(for (e <- entityNames) yield e -> Status.Online)

	val entityMap: mutable.Map[String, Vector[String]] =
		mutable.Map.empty

	def initEntityMap(): Unit = {
		assert(entityMap.isEmpty)

		entityNames.foreach { e =>
			assert(stateMap.contains(e))
			entityMap.update(e, Vector.empty[String])
		}
		var index: Int = 0
		symbolNames.foreach { symbolName =>
			val owner: String = entityNames(index)
			val current: Vector[String] = entityMap.getOrElse(owner, Vector.empty[String])
			entityMap.update(owner, current :+ symbolName)
			index = (index + 1) % entityNames.size
		}
	}

	def onEntityLost(entityName: String, onlineEntities: Vector[String]): Unit = {
		assert(!onlineEntities.contains(entityName))

		val symbols: Vector[String] = entityMap(entityName)
		entityMap.update(entityName, Vector.empty[String])

		val onlineEntsSorted: Vector[(String, Int)] =
			(for (onlineEnt <- onlineEntities) yield (onlineEnt, entityMap(onlineEnt).size))
				.toVector
				.sortBy(x => x._2)

		var index: Int = 0
		symbols.foreach { symbolName =>
			val newOwner: String = onlineEntsSorted(index)._1
			val current: Vector[String] = entityMap(newOwner)
			entityMap.update(newOwner, current :+ symbolName)
			index = (index + 1) % onlineEntsSorted.size
		}
	}

	def onEntityWon(entityName:String):Unit={
		val onlineEntities = {for (x<- entityNames if stateMap(x)==Status.Online) yield (x,entityMap(x).size)}.toVector.sortBy(x=>(-1)*x._2)
		val offlineEntities = {for (x<- entityNames if stateMap(x)==Status.Offline) yield x}.toVector

		assert(offlineEntities.contains(entityName))

		var index :Int = 0
		val atLeast = math.floor(symbolNames.size/onlineEntities.size).toInt
		var newSymbols = Vector.empty[String]
		for (i<-0 until atLeast){
			val currEnt = onlineEntities(index)._1
			var entSymbols = entityMap(currEnt)
			val last = entSymbols.last

			newSymbols = newSymbols ++ Vector(last)
			entityMap(currEnt) = entityMap(currEnt).dropRight(1)
			index = (index+1)%onlineEntities.size
		}



	}

	def apply(onlineEntities: Vector[String]): mutable.Map[String, Vector[String]] = {
		assert(onlineEntities.forall(entityNames.contains))

		if (entityMap.isEmpty) {
			initEntityMap()
		}

		entityNames.foreach { entityName: String =>
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
