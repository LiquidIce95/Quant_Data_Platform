package src.main.scala

import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable

class TestRoundRobin extends AnyFunSuite {

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

	test("big test for robin downscaling") {
		for (n <- 4 until 20) {
			for (m <- 3 until 200) {
				val symbols  = (0 until m).map(_.toString).toVector
				val entities = (0 until n).map(_.toString).toVector

				for (l <- 0 until 500) {
					val robin = new RoundRobin[String, String](entities,symbols)

					val rnd = new scala.util.Random()
					val symbolsToEntities = robin(onlineEntities = entities)

					val symbolCount = onlineSymbolCounts(robin.stateMap, symbolsToEntities)

					val min = symbolCount.min
					val max = symbolCount.max

					var maxDiff = max - min

                    assert(maxDiff<=1)

					val numberOfflineEnts = pickRandomIndex(n, rnd)

					var offlinePicks: Vector[String] = Vector.empty

					while (offlinePicks.size < numberOfflineEnts) {
						val offlinePick =
							pickRandomEntity(entities.filterNot(offlinePicks.contains), rnd)

						offlinePicks = offlinePicks :+ offlinePick
						val onlineEnts: Vector[String] =
							entities.filterNot(offlinePicks.contains)

						val updatedMap = robin(onlineEnts)

						val symbolCountNew =
							onlineSymbolCounts(robin.stateMap, updatedMap)

						// this is critical behaviour
						assert(symbolCountNew.sum == m)

						val min = symbolCountNew.min
						val max = symbolCountNew.max

						if (maxDiff < (max - min)) maxDiff = max - min
						assert(maxDiff <= 1)
					}
				}
			}
		}
	}

	test("big test for robin upscaling") {
		for (n <- 4 until 20) {
			for (m <- 3 until 200) {
				val symbols  = (0 until m).map(_.toString).toVector
				val entities = (0 until n).map(_.toString).toVector

				for (l <- 0 until 500) {
					val robin = new RoundRobin[String, String](entities,symbols)

					val rnd = new scala.util.Random()
					var onlinePicks: Vector[String] = Vector(entities(0))

					val symbolsToEntities = robin(onlineEntities = onlinePicks)

					val symbolCount = onlineSymbolCounts(robin.stateMap, symbolsToEntities)

					val min = symbolCount.min
					val max = symbolCount.max

					var maxDiff = max - min

                    assert(maxDiff<=1)

					val numberOnlineEnts = pickRandomIndex(n, rnd)


					while (onlinePicks.size < numberOnlineEnts) {
						val onlinePick = pickRandomEntity(entities.filterNot(onlinePicks.contains), rnd)

						onlinePicks = onlinePicks :+ onlinePick
						

						val updatedMap = robin(onlinePicks)

						val symbolCountNew =
							onlineSymbolCounts(robin.stateMap, updatedMap)

						// this is critical behaviour
						assert(symbolCountNew.sum == m)

						val min = symbolCountNew.min
						val max = symbolCountNew.max

						if (maxDiff < (max - min)) maxDiff = max - min
						assert(maxDiff <= 1)
					}
				}
			}
		}
	}
}
