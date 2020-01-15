package it.bitrock.dvs.streams

import java.util.Properties

import it.bitrock.dvs.streams.config.AppConfig
import it.bitrock.dvs.model.avro._
import it.bitrock.kafkacommons.serialization.ImplicitConversions._
import it.bitrock.testcommons.{FixtureLoanerAnyResult, Suite}
import net.manub.embeddedkafka.UUIDs
import net.manub.embeddedkafka.schemaregistry._
import net.manub.embeddedkafka.schemaregistry.streams.EmbeddedKafkaStreams
import org.apache.kafka.clients.producer._
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.scala.Serdes
import org.apache.kafka.streams.{KafkaStreams, StreamsConfig, Topology}
import org.scalatest.{OptionValues, WordSpecLike}

import scala.concurrent.duration._

object StreamsSpec {

  final case class Resource(
      embeddedKafkaConfig: EmbeddedKafkaConfig,
      appConfig: AppConfig,
      kafkaStreamsOptions: KafkaStreamsOptions,
      topologies: List[Topology],
      topicsToCreate: List[String]
  )

}

class StreamsSpec extends Suite with WordSpecLike with EmbeddedKafkaStreams with OptionValues with Events {

  import StreamsSpec._

  final val TopologyTestExtraConf = Map(
    // The commit interval for flushing records to state stores and downstream must be lower than
    // test's timeout (5 secs) to ensure we observe the expected processing results.
    StreamsConfig.COMMIT_INTERVAL_MS_CONFIG -> 3.seconds.toMillis.toString
  )
  final val ConsumerPollTimeout: FiniteDuration = 20.seconds

  def dummyFlightReceivedForcingSuppression(topic: String) = new ProducerRecord(
    topic,
    null,
    java.lang.System.currentTimeMillis + 1.minute.toMillis,
    "",
    FlightReceivedEvent
  )

  "Streams" should {

    "be joined successfully with consistent data" in ResourceLoaner.withFixture {
      case Resource(embeddedKafkaConfig, appConfig, kafkaStreamsOptions, topologies, topicsToCreate) =>
        implicit val embKafkaConfig: EmbeddedKafkaConfig = embeddedKafkaConfig
        implicit val keySerde: Serde[String]             = kafkaStreamsOptions.keySerde

        val receivedRecords = runStreams(topicsToCreate, topologies.head, TopologyTestExtraConf) {
          publishToKafka(appConfig.kafka.topology.flightRawTopic, FlightRawEvent.flight.icaoNumber, FlightRawEvent)
          publishToKafka(
            appConfig.kafka.topology.airportRawTopic,
            List(
              AirportEvent1.codeIataAirport -> AirportEvent1,
              AirportEvent2.codeIataAirport -> AirportEvent2
            )
          )
          publishToKafka(appConfig.kafka.topology.airlineRawTopic, AirlineEvent1.codeIcaoAirline, AirlineEvent1)
          publishToKafka(appConfig.kafka.topology.airplaneRawTopic, AirplaneEvent.numberRegistration, AirplaneEvent)
          val messagesMap = consumeNumberKeyedMessagesFromTopics[String, FlightReceived](
            topics = Set(appConfig.kafka.topology.flightReceivedTopic),
            number = 1,
            timeout = ConsumerPollTimeout
          )
          messagesMap(appConfig.kafka.topology.flightReceivedTopic).head
        }
        receivedRecords shouldBe (FlightReceivedEvent.icaoNumber, FlightReceivedEvent)
    }

    "be joined successfully with default airplane info" in ResourceLoaner.withFixture {
      case Resource(embeddedKafkaConfig, appConfig, kafkaStreamsOptions, topologies, topicsToCreate) =>
        implicit val embKafkaConfig: EmbeddedKafkaConfig = embeddedKafkaConfig
        implicit val keySerde: Serde[String]             = kafkaStreamsOptions.keySerde

        val receivedRecords = runStreams(topicsToCreate, topologies.head, TopologyTestExtraConf) {
          publishToKafka(appConfig.kafka.topology.flightRawTopic, FlightRawEvent.flight.icaoNumber, FlightRawEvent)
          publishToKafka(
            appConfig.kafka.topology.airportRawTopic,
            List(
              AirportEvent1.codeIataAirport -> AirportEvent1,
              AirportEvent2.codeIataAirport -> AirportEvent2
            )
          )
          publishToKafka(appConfig.kafka.topology.airlineRawTopic, AirlineEvent1.codeIcaoAirline, AirlineEvent1)
          val messagesMap = consumeNumberKeyedMessagesFromTopics[String, FlightReceived](
            topics = Set(appConfig.kafka.topology.flightReceivedTopic),
            number = 1,
            timeout = ConsumerPollTimeout
          )
          messagesMap(appConfig.kafka.topology.flightReceivedTopic).head
        }
        receivedRecords shouldBe (ExpectedFlightReceivedWithDefaultAirplane.icaoNumber, ExpectedFlightReceivedWithDefaultAirplane)
    }

    "produce FlightReceivedList elements in the appropriate topic" in ResourceLoaner.withFixture {
      case Resource(embeddedKafkaConfig, appConfig, kafkaStreamsOptions, topologies, topicsToCreate) =>
        implicit val embKafkaConfig: EmbeddedKafkaConfig = embeddedKafkaConfig
        implicit val keySerde: Serde[String]             = kafkaStreamsOptions.keySerde

        val receivedRecords = runStreams(topicsToCreate, topologies(1), TopologyTestExtraConf) {
          val flightMessages = 0 to 9 map { key =>
            key.toString -> FlightReceivedEvent.copy(
              iataNumber = key.toString,
              icaoNumber = key.toString
            )
          }
          publishToKafka(appConfig.kafka.topology.flightReceivedTopic, flightMessages)
          publishToKafka(dummyFlightReceivedForcingSuppression(appConfig.kafka.topology.flightReceivedTopic))
          val messagesMap = consumeNumberKeyedMessagesFromTopics[String, FlightReceivedList](
            topics = Set(appConfig.kafka.topology.flightReceivedListTopic),
            number = 1,
            timeout = ConsumerPollTimeout
          )
          messagesMap(appConfig.kafka.topology.flightReceivedListTopic).map(_._2).head
        }
        receivedRecords.elements should contain theSameElementsInOrderAs ExpectedFlightReceivedList
    }

    "produce TopArrivalAirportList elements in the appropriate topic" in ResourceLoaner.withFixture {
      case Resource(embeddedKafkaConfig, appConfig, kafkaStreamsOptions, topologies, topicsToCreate) =>
        implicit val embKafkaConfig: EmbeddedKafkaConfig = embeddedKafkaConfig
        implicit val keySerde: Serde[String]             = kafkaStreamsOptions.keySerde

        val receivedRecords = ResourceLoaner.runAll(topicsToCreate, topologies) { _ =>
          val flightMessages = 1 to 40 map { key =>
            val codeIataAirport = key match {
              case x if x >= 1 && x <= 3   => AirportEvent1.codeIataAirport
              case x if x >= 4 && x <= 9   => AirportEvent2.codeIataAirport
              case x if x >= 10 && x <= 18 => AirportEvent3.codeIataAirport
              case x if x >= 19 && x <= 20 => AirportEvent4.codeIataAirport
              case x if x >= 21 && x <= 24 => AirportEvent5.codeIataAirport
              case x if x >= 25 && x <= 29 => AirportEvent6.codeIataAirport
              case x if x >= 30 && x <= 40 => AirportEvent7.codeIataAirport
            }
            key.toString -> FlightReceivedEvent.copy(
              iataNumber = key.toString,
              icaoNumber = key.toString,
              airportArrival = AirportInfo(codeIataAirport, "", "", "", "", "")
            )
          }
          publishToKafka(appConfig.kafka.topology.flightReceivedTopic, flightMessages)
          publishToKafka(dummyFlightReceivedForcingSuppression(appConfig.kafka.topology.flightReceivedTopic))
          val messagesMap = consumeNumberKeyedMessagesFromTopics[String, TopArrivalAirportList](
            topics = Set(appConfig.kafka.topology.topArrivalAirportTopic),
            number = 1,
            timeout = ConsumerPollTimeout
          )
          messagesMap(appConfig.kafka.topology.topArrivalAirportTopic).head._2
        }
        receivedRecords.elements.size shouldBe 5
        receivedRecords.elements should contain theSameElementsInOrderAs ExpectedTopArrivalResult.elements
    }

    "produce TopDepartureAirportList elements in the appropriate topic" in ResourceLoaner.withFixture {
      case Resource(embeddedKafkaConfig, appConfig, kafkaStreamsOptions, topologies, topicsToCreate) =>
        implicit val embKafkaConfig: EmbeddedKafkaConfig = embeddedKafkaConfig
        implicit val keySerde: Serde[String]             = kafkaStreamsOptions.keySerde

        val receivedRecords = ResourceLoaner.runAll(topicsToCreate, topologies) { _ =>
          val flightMessages = 1 to 40 map { key =>
            val codeIataAirport = key match {
              case x if x >= 1 && x <= 3   => AirportEvent1.codeIataAirport
              case x if x >= 4 && x <= 9   => AirportEvent2.codeIataAirport
              case x if x >= 10 && x <= 18 => AirportEvent3.codeIataAirport
              case x if x >= 19 && x <= 20 => AirportEvent4.codeIataAirport
              case x if x >= 21 && x <= 24 => AirportEvent5.codeIataAirport
              case x if x >= 25 && x <= 29 => AirportEvent6.codeIataAirport
              case x if x >= 30 && x <= 40 => AirportEvent7.codeIataAirport
            }
            key.toString -> FlightReceivedEvent.copy(
              iataNumber = key.toString,
              icaoNumber = key.toString,
              airportDeparture = AirportInfo(codeIataAirport, "", "", "", "", "")
            )
          }
          publishToKafka(appConfig.kafka.topology.flightReceivedTopic, flightMessages)
          publishToKafka(dummyFlightReceivedForcingSuppression(appConfig.kafka.topology.flightReceivedTopic))
          val messagesMap = consumeNumberKeyedMessagesFromTopics[String, TopDepartureAirportList](
            topics = Set(appConfig.kafka.topology.topDepartureAirportTopic),
            number = 1,
            timeout = ConsumerPollTimeout
          )
          messagesMap(appConfig.kafka.topology.topDepartureAirportTopic).head._2
        }
        receivedRecords.elements.size shouldBe 5
        receivedRecords.elements should contain theSameElementsInOrderAs ExpectedTopDepartureResult.elements
    }

    "produce TopSpeedList elements in the appropriate topic" in ResourceLoaner.withFixture {
      case Resource(embeddedKafkaConfig, appConfig, kafkaStreamsOptions, topologies, topicsToCreate) =>
        implicit val embKafkaConfig: EmbeddedKafkaConfig = embeddedKafkaConfig
        implicit val keySerde: Serde[String]             = kafkaStreamsOptions.keySerde

        val receivedRecords = ResourceLoaner.runAll(topicsToCreate, topologies) { _ =>
          val flightMessages = 0 to 9 map { key =>
            key.toString -> FlightReceivedEvent.copy(
              iataNumber = key.toString,
              icaoNumber = key.toString,
              speed = SpeedArray(key)
            )
          }
          publishToKafka(appConfig.kafka.topology.flightReceivedTopic, flightMessages)
          publishToKafka(dummyFlightReceivedForcingSuppression(appConfig.kafka.topology.flightReceivedTopic))
          val messagesMap = consumeNumberKeyedMessagesFromTopics[String, TopSpeedList](
            topics = Set(appConfig.kafka.topology.topSpeedTopic),
            number = 1,
            timeout = ConsumerPollTimeout
          )
          messagesMap(appConfig.kafka.topology.topSpeedTopic).head._2
        }
        receivedRecords.elements.size shouldBe 5
        receivedRecords.elements should contain theSameElementsInOrderAs ExpectedTopSpeedResult.elements
    }

    "produce TopAirlineList elements in the appropriate topic" in ResourceLoaner.withFixture {
      case Resource(embeddedKafkaConfig, appConfig, kafkaStreamsOptions, topologies, topicsToCreate) =>
        implicit val embKafkaConfig: EmbeddedKafkaConfig = embeddedKafkaConfig
        implicit val keySerde: Serde[String]             = kafkaStreamsOptions.keySerde

        val receivedRecords = ResourceLoaner.runAll(topicsToCreate, topologies) { _ =>
          val flightMessages = 1 to 40 map { key =>
            val (codeAirline, nameAirline) = key match {
              case x if x >= 1 && x <= 3   => (AirlineEvent1.codeIcaoAirline, AirlineEvent1.nameAirline)
              case x if x >= 4 && x <= 9   => (AirlineEvent2.codeIcaoAirline, AirlineEvent2.nameAirline)
              case x if x >= 10 && x <= 18 => (AirlineEvent3.codeIcaoAirline, AirlineEvent3.nameAirline)
              case x if x >= 19 && x <= 20 => (AirlineEvent4.codeIcaoAirline, AirlineEvent4.nameAirline)
              case x if x >= 21 && x <= 24 => (AirlineEvent5.codeIcaoAirline, AirlineEvent5.nameAirline)
              case x if x >= 25 && x <= 29 => (AirlineEvent6.codeIcaoAirline, AirlineEvent6.nameAirline)
              case x if x >= 30 && x <= 40 => (AirlineEvent7.codeIcaoAirline, AirlineEvent7.nameAirline)
            }
            key.toString -> FlightReceivedEvent.copy(
              iataNumber = key.toString,
              icaoNumber = key.toString,
              airline = AirlineInfo(codeAirline, nameAirline, "")
            )
          }
          publishToKafka(appConfig.kafka.topology.flightReceivedTopic, flightMessages)
          publishToKafka(dummyFlightReceivedForcingSuppression(appConfig.kafka.topology.flightReceivedTopic))
          val messagesMap = consumeNumberKeyedMessagesFromTopics[String, TopAirlineList](
            topics = Set(appConfig.kafka.topology.topAirlineTopic),
            number = 1,
            timeout = ConsumerPollTimeout
          )
          messagesMap(appConfig.kafka.topology.topAirlineTopic).head._2
        }
        receivedRecords.elements.size shouldBe 5
        receivedRecords.elements should contain theSameElementsInOrderAs ExpectedTopAirlineResult.elements
    }

    "produce TotalFlight elements in the appropriate topic" in ResourceLoaner.withFixture {
      case Resource(embeddedKafkaConfig, appConfig, kafkaStreamsOptions, topologies, topicsToCreate) =>
        implicit val embKafkaConfig: EmbeddedKafkaConfig = embeddedKafkaConfig
        implicit val keySerde: Serde[String]             = kafkaStreamsOptions.keySerde

        val receivedRecords = ResourceLoaner.runAll(topicsToCreate, topologies) { _ =>
          val flightMessages = 0 to 9 map { key =>
            key.toString -> FlightReceivedEvent.copy(
              iataNumber = key.toString,
              icaoNumber = key.toString
            )
          }
          publishToKafka(appConfig.kafka.topology.flightReceivedTopic, flightMessages)
          publishToKafka(dummyFlightReceivedForcingSuppression(appConfig.kafka.topology.flightReceivedTopic))
          val messagesMap = consumeNumberKeyedMessagesFromTopics[String, CountFlight](
            topics = Set(appConfig.kafka.topology.totalFlightTopic),
            number = 1,
            timeout = ConsumerPollTimeout
          )
          messagesMap(appConfig.kafka.topology.totalFlightTopic).map(_._2).head
        }
        receivedRecords.eventCount shouldBe ExpectedTotalFlightResult
    }

    "produce TotalAirline elements in the appropriate topic" in ResourceLoaner.withFixture {
      case Resource(embeddedKafkaConfig, appConfig, kafkaStreamsOptions, topologies, topicsToCreate) =>
        implicit val embKafkaConfig: EmbeddedKafkaConfig = embeddedKafkaConfig
        implicit val keySerde: Serde[String]             = kafkaStreamsOptions.keySerde

        val receivedRecords = ResourceLoaner.runAll(topicsToCreate, topologies) { _ =>
          val flightMessages = 0 to 9 map { key =>
            key.toString -> FlightReceivedEvent.copy(
              iataNumber = key.toString,
              icaoNumber = key.toString,
              airline = AirlineInfo(CodeAirlineArray(key), "", "")
            )
          }
          publishToKafka(appConfig.kafka.topology.flightReceivedTopic, flightMessages)
          publishToKafka(dummyFlightReceivedForcingSuppression(appConfig.kafka.topology.flightReceivedTopic))
          val messagesMap = consumeNumberKeyedMessagesFromTopics[String, CountAirline](
            topics = Set(appConfig.kafka.topology.totalAirlineTopic),
            number = 1,
            timeout = ConsumerPollTimeout
          )
          messagesMap(appConfig.kafka.topology.totalAirlineTopic).map(_._2).head
        }
        receivedRecords.eventCount shouldBe ExpectedTotalAirlineResult
    }
  }

  object ResourceLoaner extends FixtureLoanerAnyResult[Resource] {
    override def withFixture(body: Resource => Any): Any = {
      implicit lazy val embeddedKafkaConfig: EmbeddedKafkaConfig = EmbeddedKafkaConfig()

      val appConfig: AppConfig = {
        val conf         = AppConfig.load
        val topologyConf = conf.kafka.topology.copy(aggregationTimeWindowSize = 5.seconds, aggregationTotalTimeWindowSize = 5.seconds)
        conf.copy(kafka = conf.kafka.copy(topology = topologyConf))
      }

      val kafkaStreamsOptions = KafkaStreamsOptions(
        Serdes.String,
        specificAvroValueSerde[FlightRaw],
        specificAvroValueSerde[AirportRaw],
        specificAvroValueSerde[AirlineRaw],
        specificAvroValueSerde[CityRaw],
        specificAvroValueSerde[AirplaneRaw],
        specificAvroValueSerde[FlightWithDepartureAirportInfo],
        specificAvroValueSerde[FlightWithAllAirportInfo],
        specificAvroValueSerde[FlightWithAirline],
        specificAvroValueSerde[FlightReceived],
        specificAvroValueSerde[FlightReceivedList],
        Serdes.Long,
        specificAvroValueSerde[TopArrivalAirportList],
        specificAvroValueSerde[TopDepartureAirportList],
        specificAvroValueSerde[Airport],
        specificAvroValueSerde[TopSpeedList],
        specificAvroValueSerde[SpeedFlight],
        specificAvroValueSerde[TopAirlineList],
        specificAvroValueSerde[Airline],
        specificAvroValueSerde[CountFlight],
        specificAvroValueSerde[CountAirline],
        specificAvroValueSerde[CodeAirlineList],
        specificAvroValueSerde[FlightNumberList]
      )
      val topologies = Streams.buildTopology(appConfig, kafkaStreamsOptions)

      body(
        Resource(
          embeddedKafkaConfig,
          appConfig,
          kafkaStreamsOptions,
          topologies.map(_._1),
          List()
        )
      )
    }

    def runAll[A](topicsToCreate: Seq[String], topologies: List[Topology])(body: List[KafkaStreams] => A): A = {
      runStreams(topicsToCreate, topologies.head, TopologyTestExtraConf) {
        import scala.collection.JavaConverters._

        val streams = topologies.tail.map(topology => {
          val streamsConf = streamsConfig.config(UUIDs.newUuid().toString, TopologyTestExtraConf)
          val props       = new Properties
          props.putAll(streamsConf.asJava)
          val otherStream = new KafkaStreams(topology, props)
          otherStream.start()
          otherStream
        })

        val result = body(streams)
        streams.foreach(_.close())
        result
      }
    }
  }

}