package src.main.scala

import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable

class TestRendevouzHasher extends AnyFunSuite {

	def pickRandomIndex(n: Int, rnd: scala.util.Random): Int = {
		require(n > 0)
		rnd.nextInt(n)    // 0 .. n-1
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

	test("rendevouz 'hashing' with our score table") {

		for (n <- 1 until 8) {
			for (m <- 1 until 80) {
				val rnd = new scala.util.Random()

				val symbols  = (0 until m).map(_.toString).toList
				val entities = (0 until n).map(_.toString).toList

				val scoreTable = new OptimalDistributionFunction(m, n)
				assert(scoreTable.thisMatrixFine(),"this TAble is not fine")

				val rendevouzH = new RendevouzHasher {
					protected val entityNames: List[String] = entities
					protected val symbolNames: List[String] = symbols

					def scoreFunction(symbol: String, entityName: String): Int = {
						val symbolInt = symbol.toInt
						val entityInt = entityName.toInt
						scoreTable.distributionMatrix(symbolInt)(entityInt)
					}

					val stateMap: mutable.Map[String, Status] =
						mutable.Map.from(for (e <- entities) yield e -> Status.Online)
				}

				// all entities online
				val symbolsToEntities = rendevouzH(onlineEntities = entities)

				// initial balance check
				val symbolCount = onlineSymbolCounts(rendevouzH.stateMap, symbolsToEntities)
				val min0 = symbolCount.min
				val max0 = symbolCount.max
				assert(max0 - min0 <= 1,"min max comparison not good when all are online")

				// randomly take entities offline one by one, checking balance each time
				val numberOfflineEnts = pickRandomIndex(n, rnd) // in [0, n-1]

				var offlinePicks: Set[String] = Set.empty
				var k = 0
				while (k < numberOfflineEnts && offlinePicks.size < entities.size) {
					// pick next offline entity from those still online
					val available = entities.filterNot(offlinePicks.contains)
					val nextOffline = pickRandomEntity(available, rnd)
					offlinePicks = offlinePicks + nextOffline

					val onlineEnts: List[String] =
						entities.filterNot(offlinePicks.contains)

					val updatedMap = rendevouzH(onlineEnts)

					val symbolCountNew = onlineSymbolCounts(rendevouzH.stateMap, updatedMap)
					val minNew = symbolCountNew.min
					val maxNew = symbolCountNew.max
					assert(maxNew - minNew <= 1,"min max comparison not good ")

					k = k + 1
				}
			}
		}
	}
}
