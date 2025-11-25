package src.main.scala

import org.scalatest.funsuite.AnyFunSuite
import src.main.scala.Algorithms.OptimalDistributionFunction

class TestOptimalDistributionFunction extends AnyFunSuite {
	def fact(n: Int): Int = {
		var r = 1
		for (k <- 1 to n) {
			r = r * k
		}
		r
	}

	test("testing the ideal factorial case all entities should have the same number of scores") {
		for (n <- 1 until 10) {
			val m = fact(n)
			val d = new OptimalDistributionFunction(m, n)
			assert(d.thisMatrixFine())
			println(f"matrix is fine for size n:$n m:$m")

			val A = d.distributionMatrix

			for (k <- 0 until n) {
				var lastNumberOfScores = 0
				for (j <- 0 until n) {
					val col = for (i <- 0 until m) yield A(i)(j)
					val scores = for (x <- col if x == k) yield x
					val numScore = scores.size
					if (lastNumberOfScores == 0) (lastNumberOfScores = numScore)
					println(f"score : $k, entity : $j, ")
					assert(numScore == lastNumberOfScores)
					lastNumberOfScores = numScore
				}
			}
		}
        assert(true)
	}
}
