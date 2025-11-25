package src.main.scala

import scala.collection.mutable

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

    private val garbageValue = 0

    val offsets :mutable.Map[(Vector[Int],Int),Int]= mutable.Map.empty

    def offlineEntities(A:Array[Array[Int]],row:Int,n:Int):Vector[Int]={
        val entitiesOffline = (0 until n).map{
            j=> if (A(row)(j)!= garbageValue) 1 else 0
        }.toVector
        entitiesOffline
    }

    def initMatrix (A : Array[Array[Int]],m:Int,n:Int):Array[Array[Int]]={
        
        for (i<-0 until m){
            for (j<-0 until n){
                A(i)(j)= garbageValue
            }
        }

        var originalOffset =0
        for (i<-0 until m){
            A(i)(i%(n))=n-1;
            originalOffset=i%n
        }

        originalOffset=(originalOffset+1)%n

        for (i<-0 until m){
            val E = offlineEntities(A,i,n)
            offsets((E,n-1))=originalOffset
        }



        for (k<-n-2 to 1 by -1) {
            for (j<-0 until n){
                for (i<-0 until m){
                    val E = offlineEntities(A,i,n)
                    if (A(i)(j)== k+1) {
                        if (!offsets.contains((E,k))) {
                            offsets((E,k))=offsets((E,k+1))
                        }
                        while (A(i)(offsets((E,k)))!=garbageValue){
                            offsets((E,k)) = (offsets((E,k))+1)%(n)
                        }
                        A(i)(offsets((E,k)))=k
                        offsets((E,k)) = (offsets((E,k))+1)%n
                        val E_ = offlineEntities(A,i,n) 
                        offsets((E_,k)) = offsets((E,k))
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
