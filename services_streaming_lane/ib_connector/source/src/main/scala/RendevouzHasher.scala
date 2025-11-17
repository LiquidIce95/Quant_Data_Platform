package src.main.scala

object RendevouzHasher {

    /**
      * asssigns to each symbol we want to shard a score
      * It should be an ideal hashfunction
      *
      * @param symbol the trading symbol we want to stream data for 
        @param podName The name of some pod in the ib connector namespace
      * @return
      */
    def scoreFunction(symbol:String,podName:String):Int={
        1
    } 



}