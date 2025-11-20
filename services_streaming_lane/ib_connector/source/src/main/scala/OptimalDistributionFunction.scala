package src.main.scala

class OptimalDistributionFunction(
    val m:Int,
    val n:Int
) {
    def fact(n: Int): Int = {
		var r = 1
		for (k <- 1 to n) {
			r = r * k
		}
		r
	}

    def initMatrix (A : Array[Array[Int]],m:Int,n:Int):Array[Array[Int]]={
        for (i<-0 until m){
            A(i)(i%(n))=n-1;
        }

        for (k<-n-2 to 1 by -1) {
            for (j<-0 until n){
                var index = 0 
                for (i<-0 until m){
                    if (A(i)(j)== k+1) {
                        while (A(i)(index)!=0){
                            index = (index+1)%(n)
                        }
                        A(i)(index)=k
                        index = (index+1)%n
                    }
                }
            }
        }

        A
    }

    val distributionMatrix : Array[Array[Int]] = { 
        var A = Array.ofDim[Int](m,n)
        initMatrix(A,m,n)
    }

    def checkMatrix(A:Array[Array[Int]]):Boolean={
        var i = 0
        for (i <- 0 until m){
            var seen = Array.ofDim[Int](n)
            var j = 0
            for (j <- 0 until n){
                var v = A(i)(j)
                assert(v >= 0)
                assert(v < n)
                assert(seen(v) == 0)
                seen(v) = 1
            }
            var k = 0
            for (k <- 0 until n){
                assert(seen(k) == 1)
            }
        }

        true

    }

    def thisMatrixFine():Boolean={
        checkMatrix(distributionMatrix)
    }

}

object TestOptimalDistributionFunction {
    def fact(n: Int): Int = {
		var r = 1
		for (k <- 1 to n) {
			r = r * k
		}
		r
	}
    def main(args:Array[String]):Unit={
        for (n <- 1 until 6) {
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