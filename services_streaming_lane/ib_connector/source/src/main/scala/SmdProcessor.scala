package src.main.scala

import upickle.core.LinkedHashMap
import ujson._

/** Processes IBKR SMD tick frames and emits normalized ticks to Kafka. */
class SmdProcessor(
	producerApi: KafkaProducerApi,
	api:ApiHandler
) {

	val symbolUniverse: Vector[(Long, String, String)] =
		api.computeSymbolUniverse()

	
	private val conidToCode: Map[Long, String] = {
		val m = scala.collection.mutable.Map[Long, String]()
		var i = 0
		while (i < symbolUniverse.length) {
			val triplet = symbolUniverse(i)
			val conId = triplet._1
			val expiry = triplet._2
			val symbol = triplet._3
			val code = CommonUtils.buildMonthYearCode(expiry)
			m.update(conId, symbol+code)
			i = i + 1
		}
		m.toMap
	}

	private case class TickState(
        var tradingSymbol : Option[String],
		var price: Option[String],
		var size: Option[String],
		var openInterest: Option[String],
		var eventTime: Option[String],
		var marketDataType: Option[String]
	)

	private val stateByConId = {
		val m = scala.collection.mutable.Map[Long, TickState]()
        var i = 0
		while (i < symbolUniverse.length) {
			val pair = symbolUniverse(i)
			val conId = pair._1
			val expiry = pair._2
			val tick = TickState(None,None,None,None,None,None)
			m.update(conId, tick)
			i = i + 1
		}
		m
    }

	private val topic =
		"ticklast"

    private def valueAsString(v: ujson.Value): String =
		v match {
			case Str(s) => s
			case Num(n) => n.toString
			case _      => v.toString
		}

	/** Handles a single SMD frame and emits a full tick if any field changed. */
	def apply(message: LinkedHashMap[String, ujson.Value]): Unit = {
		assert(message.contains("conid"))

		val conIdValue = message("conid")
		val conId: Long =
			conIdValue match {
				case Num(n) => n.toLong
				case Str(s) => s.toLong
				case _      => throw new IllegalArgumentException("conid has unsupported type: " + conIdValue.toString)
			}

		val tick =
			stateByConId.getOrElseUpdate(
				conId,
				new TickState(None, None, None, None, None, None)
			)

		message.foreach {
			case (key, value) =>
				key match {
					case "31" =>
						tick.price = Some(valueAsString(value))
					case "55" =>
						tick.tradingSymbol = Some(conidToCode(conId))
					case "6509" =>
						tick.marketDataType = Some(valueAsString(value))
					case "7059" =>
						tick.size = Some(valueAsString(value))
					case "7697" =>
						tick.openInterest = Some(valueAsString(value))
					case "_updated" =>
						tick.eventTime = Some(valueAsString(value))
					case _ =>
						()
				}
		}

		if (
			tick.tradingSymbol.isDefined &&
			tick.price.isDefined &&
			tick.size.isDefined &&
			tick.openInterest.isDefined &&
			tick.eventTime.isDefined &&
			tick.marketDataType.isDefined
		) {
			val json =
				Obj(
					"symbol" -> Str(tick.tradingSymbol.get),
					"conid" -> Str(conId.toString),
					"price" -> Str(tick.price.get),
					"size" -> Str(tick.size.get),
					"open_interest" -> Str(tick.openInterest.get),
					"event_time" -> Str(tick.eventTime.get),
					"market_data_type" -> Str(tick.marketDataType.get)
				)

			val payload = write(json)

			producerApi.send(
				topic,
				conId,
				payload
			)
		}
	}
				
	
}
