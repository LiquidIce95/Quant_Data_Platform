package src.main.scala
import upickle.core.LinkedHashMap
import ujson._
import scala.collection.mutable

// will have states for each ConId of the smd topic and update those when new messges (string) arrive 
// its apply method will override onSmdFrame in StreamManager, sends request in form of conId to the requestSetSmd

class SbdProcessor(
    producerApi:KafkaProducerApi
    ) {

    val symbolUniverse :List[(Long,String)]= ApiHandler.computeSymbolUniverse()

    private def buildMonthYearCode(expiry: String): String = {
          assert(expiry.length()==8)

          val yearSuffix = expiry.substring(2, 4)
          val monthStr = expiry.substring(4, 6)
          val monthOpt = monthStr.toIntOption
          monthOpt match {
              case Some(m) =>
                  val monthCode =
                      m match {
                          case 1  => "F"
                          case 2  => "G"
                          case 3  => "H"
                          case 4  => "J"
                          case 5  => "K"
                          case 6  => "M"
                          case 7  => "N"
                          case 8  => "Q"
                          case 9  => "U"
                          case 10 => "V"
                          case 11 => "X"
                          case 12 => "Z"
                          case _  => ""
                      }
                  monthCode + yearSuffix
              case None =>
                  expiry
          }
      
    }

    private val conidToCode: Map[Long, String] = {
      val m = scala.collection.mutable.Map[Long, String]()
      var i = 0
      while (i < symbolUniverse.length) {
        val pair = symbolUniverse(i)
        val conId = pair._1
        val expiry = pair._2
        val code = buildMonthYearCode(expiry)
        m.update(conId, code)
        i = i + 1
      }
      m.toMap
    }

    /**
      * the first map is row -> price,side,size 
      * the second map is level,side -> price,size
      * 
      */
    val conIdToMaps : mutable.Map[Long,(mutable.Map[Int,(Double,String,Int)],mutable.Map[(Int,String),(Double,Int)])] = {
      val m = mutable.Map[Long,(mutable.Map[Int,(Double,String,Int)],mutable.Map[(Int,String),(Double,Int)])]()
      var i = 0
      
      while(i< symbolUniverse.length){
        val pair = symbolUniverse(i)
        val conId = pair._1 

        val rowMap = mutable.Map[Int,(Double,String,Int)]()
        val levelMap = mutable.Map[(Int,String),(Double,Int)]()
        
        m.update(conId,(rowMap,levelMap))
        i = i+1
      }
      m
    }
    
    /**
      * will overwrite the StreamManagers abstract member 'onSbdFrame'
      * UNFINISHED IMPLEMENTATION we noticed that the web api docs are not well enough to understand 
      * how we should process incoming data...
      * @param message raw json stirng
      */
    def apply(message: LinkedHashMap[String, ujson.Value]):Unit={
      assert(message.contains("conid"))
      assert(message.contains("data"))

      val conIdValue = message("conid")
      val conId: Long =
        conIdValue match {
          case Num(n) => n.toLong
          case Str(s) => s.toLong
          case _      => throw new IllegalArgumentException("conid has unsupported type: " + conIdValue.toString)
        }

      val (rowMap,levelMap) = conIdToMaps(conId)

      val dataRows : ujson.Arr = message("data").arr
      assert(dataRows.contentLength!=None)

      var i = 0
      while (i<dataRows.contentLength.get){
        val row : LinkedHashMap[String,ujson.Value] = dataRows(i).obj
        val keys = row.keys



      }

    }

}