package src.main.scala

import upickle.core.LinkedHashMap
import ujson._
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import java.security.MessageDigest

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
			m.update(conId, symbol + code)
			i = i + 1
		}
		m.toMap
	}

	private case class TickState(
		var tradingSymbol: Option[String],
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
			val tick = TickState(None, None, None, None, None, None)
			m.update(conId, tick)
			i = i + 1
		}
		m
	}

	private val topic =
		"derivatives_tick_market_data"

	private val avroSchema: Schema = {
		val schemaFile = new java.io.File("src/main/scala/category_schemas/derivatives_tick_market_data.avsc")
		assert(schemaFile.exists())
		new Schema.Parser().parse(schemaFile)
	}

	private def sha256Hex(s: String): String = {
		val md = MessageDigest.getInstance("SHA-256")
		val bytes = md.digest(s.getBytes("UTF-8"))
		val sb = new StringBuilder(bytes.length * 2)
		var i = 0
		while (i < bytes.length) {
			sb.append(String.format("%02x", Byte.box(bytes(i))))
			i = i + 1
		}
		sb.toString
	}

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
			val sourceSystemName = "interactive_brokers_web_api_realtime_stream"
			val componentInstanceName = "ib_connector"

			val ingestionTimeMs: Long =
				System.currentTimeMillis()

			val exchangeCode = "CME"
			val symbolCode = tick.tradingSymbol.get
			val symbolType = "futures"

			val price: Double =
				tick.price.get.toDouble

			val priceUnit = "1_USdollar"

			val size: Double =
				tick.size.get.toDouble

			val sizeUnit = "1_contract"

			val tickTimeMs: Long =
				tick.eventTime.get.toLong

			val openInterest: Long =
				tick.openInterest.get.toDouble.toLong

			val openInterestUnit = "1000_contract"

			val eventRefInput =
				sourceSystemName + "\u0001" +
				exchangeCode + "\u0001" +
				symbolCode + "\u0001" +
				symbolType + "\u0001" +
				tickTimeMs.toString + "\u0001" +
				price.toString + "\u0001" +
				priceUnit + "\u0001" +
				size.toString + "\u0001" +
				sizeUnit

			val eventRef =
				sha256Hex(eventRefInput)

			val rec: GenericRecord =
				new GenericData.Record(avroSchema)

			rec.put("source_system_name", sourceSystemName)
			rec.put("component_instance_name", componentInstanceName)
			rec.put("ingestion_time", ingestionTimeMs)
			rec.put("exchange_code", exchangeCode)
			rec.put("symbol_code", symbolCode)
			rec.put("symbol_type", symbolType)
			rec.put("price", price)
			rec.put("price_unit", priceUnit)
			rec.put("size", size)
			rec.put("size_unit", sizeUnit)
			rec.put("tick_time", tickTimeMs)
			rec.put("event_ref", eventRef)
			rec.put("open_interest", Long.box(openInterest))
			rec.put("open_interest_unit", openInterestUnit)

			producerApi.sendAvro(
				topic,
				conId,
				rec
			)
		}
	}
}
