package it.bitrock.dvs.streams

import java.util.Properties

import io.confluent.kafka.serializers.{AbstractKafkaAvroSerDeConfig, KafkaAvroDeserializerConfig}
import it.bitrock.dvs.streams.config.{AppConfig, KafkaConfig}
import it.bitrock.dvs.model.avro._
import org.apache.kafka.clients.consumer.{ConsumerConfig, OffsetResetStrategy}
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.kstream.{GlobalKTable, TimeWindows}
import org.apache.kafka.streams.scala.ImplicitConversions._
import org.apache.kafka.streams.scala.StreamsBuilder
import org.apache.kafka.streams.scala.kstream.Suppressed.BufferConfig
import org.apache.kafka.streams.scala.kstream.{KStream, Suppressed}
import org.apache.kafka.streams.{StreamsConfig, Topology}

object Streams {

  // Whether or not to deserialize `SpecificRecord`s when possible
  final val UseSpecificAvroReader   = true
  final val AutoOffsetResetStrategy = OffsetResetStrategy.EARLIEST
  final val AllRecordsKey: String   = "all"

  def streamProperties(config: KafkaConfig): Properties = {
    val props = new Properties
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
    props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, config.topology.threadsAmount.toString)
    props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, config.topology.commitInterval.toMillis.toString)
    props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, config.topology.cacheMaxSizeBytes.toString)
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, AutoOffsetResetStrategy.toString.toLowerCase)
    props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, config.topology.maxRequestSize.toString)
    props.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, config.schemaRegistryUrl.toString)
    props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, UseSpecificAvroReader.toString)
    props.put(StreamsConfig.TOPOLOGY_OPTIMIZATION, StreamsConfig.OPTIMIZE)

    props
  }

  def buildTopology(config: AppConfig, kafkaStreamsOptions: KafkaStreamsOptions): List[(Topology, Properties)] = {
    implicit val KeySerde: Serde[String]              = kafkaStreamsOptions.keySerde
    implicit val flightRawSerde: Serde[FlightRaw]     = kafkaStreamsOptions.flightRawSerde
    implicit val airportRawSerde: Serde[AirportRaw]   = kafkaStreamsOptions.airportRawSerde
    implicit val airlineRawSerde: Serde[AirlineRaw]   = kafkaStreamsOptions.airlineRawSerde
    implicit val airplaneRawSerde: Serde[AirplaneRaw] = kafkaStreamsOptions.airplaneRawSerde
    //implicit val cityRawSerde: Serde[CityRaw] = kafkaStreamsOptions.cityRawSerde

    //output topic
    implicit val flightReceivedEventSerde: Serde[FlightReceived]              = kafkaStreamsOptions.flightReceivedEventSerde
    implicit val flightReceivedListEventSerde: Serde[FlightReceivedList]      = kafkaStreamsOptions.flightReceivedListEventSerde
    implicit val topAggregationKeySerde: Serde[Long]                          = kafkaStreamsOptions.topAggregationKeySerde
    implicit val topArrivalAirportListSerde: Serde[TopArrivalAirportList]     = kafkaStreamsOptions.topArrivalAirportListEventSerde
    implicit val topDepartureAirportListSerde: Serde[TopDepartureAirportList] = kafkaStreamsOptions.topDepartureAirportListEventSerde
    implicit val topSpeedListSerde: Serde[TopSpeedList]                       = kafkaStreamsOptions.topSpeedListEventSerde
    implicit val topAirlineListSerde: Serde[TopAirlineList]                   = kafkaStreamsOptions.topAirlineListEventSerde
    implicit val topAirportSerde: Serde[Airport]                              = kafkaStreamsOptions.topAirportEventSerde
    implicit val topSpeedSerde: Serde[SpeedFlight]                            = kafkaStreamsOptions.topSpeedFlightEventSerde
    implicit val topAirlineSerde: Serde[Airline]                              = kafkaStreamsOptions.topAirlineEventSerde
    implicit val countFlightSerde: Serde[CountFlight]                         = kafkaStreamsOptions.countFlightEventSerde
    implicit val countAirlineSerde: Serde[CountAirline]                       = kafkaStreamsOptions.countAirlineEventSerde
    implicit val codeAirlineListSerde: Serde[CodeAirlineList]                 = kafkaStreamsOptions.codeAirlineListEventSerde
    implicit val flightNumberListSerde: Serde[FlightNumberList]               = kafkaStreamsOptions.flightNumberListEventSerde

    def buildFlightReceivedStreamsBuilder: (StreamsBuilder, String) = {
      val streamsBuilder   = new StreamsBuilder
      val flightRawStream  = streamsBuilder.stream[String, FlightRaw](config.kafka.topology.flightRawTopic)
      val airportRawTable  = streamsBuilder.globalTable[String, AirportRaw](config.kafka.topology.airportRawTopic)
      val airlineRawTable  = streamsBuilder.globalTable[String, AirlineRaw](config.kafka.topology.airlineRawTopic)
      val airplaneRawTable = streamsBuilder.globalTable[String, AirplaneRaw](config.kafka.topology.airplaneRawTopic)

      val flightJoinAirport: KStream[String, FlightWithAllAirportInfo] = flightRawToAirportEnrichment(flightRawStream, airportRawTable)

      val flightAirportAirline: KStream[String, FlightWithAirline] =
        flightWithAirportToAirlineEnrichment(flightJoinAirport, airlineRawTable)

      val flightAirportAirlineAirplane: KStream[String, FlightReceived] =
        flightWithAirportAndAirlineToAirplaneEnrichment(flightAirportAirline, airplaneRawTable)

      flightAirportAirlineAirplane.to(config.kafka.topology.flightReceivedTopic)
      (streamsBuilder, config.kafka.topology.flightReceivedTopic)
    }

    def flightReceivedListStreamsBuilder: (StreamsBuilder, String) = {
      val streamsBuilder = new StreamsBuilder
      streamsBuilder
        .stream[String, FlightReceived](config.kafka.topology.flightReceivedTopic)
        .groupBy((_, _) => AllRecordsKey)
        .windowedBy(TimeWindows.of(duration2JavaDuration(config.kafka.topology.aggregationTimeWindowSize)))
        .aggregate(FlightReceivedList())((_, v, agg) => FlightReceivedList(agg.elements :+ v))
        .toStream
        .map((k, v) => (k.window.start.toString, v))
        .to(config.kafka.topology.flightReceivedListTopic)
      (streamsBuilder, config.kafka.topology.flightReceivedListTopic)
    }

    def buildTopArrivalStreamsBuilder: (StreamsBuilder, String) = {
      val streamsBuilder              = new StreamsBuilder
      val topArrivalAirportAggregator = new TopArrivalAirportAggregator(config.topElementsAmount)

      streamsBuilder
        .stream[String, FlightReceived](config.kafka.topology.flightReceivedTopic)
        .groupBy((_, v) => v.airportArrival.codeAirport)
        .windowedBy(
          TimeWindows
            .of(duration2JavaDuration(config.kafka.topology.aggregationTimeWindowSize))
            .grace(duration2JavaDuration(config.kafka.topology.aggregationTimeWindowGrace))
        )
        .count
        .suppress(Suppressed.untilWindowCloses(BufferConfig.unbounded()))
        .groupBy((k, v) => (k.window.start.toString, Airport(k.key, v)))
        .aggregate(topArrivalAirportAggregator.initializer)(topArrivalAirportAggregator.adder, topArrivalAirportAggregator.subtractor)
        .toStream
        .to(config.kafka.topology.topArrivalAirportTopic)
      (streamsBuilder, config.kafka.topology.topArrivalAirportTopic)
    }

    def buildTopDepartureStreamsBuilder: (StreamsBuilder, String) = {
      val streamsBuilder                = new StreamsBuilder
      val topDepartureAirportAggregator = new TopDepartureAirportAggregator(config.topElementsAmount)

      streamsBuilder
        .stream[String, FlightReceived](config.kafka.topology.flightReceivedTopic)
        .groupBy((_, v) => v.airportDeparture.codeAirport)
        .windowedBy(
          TimeWindows
            .of(duration2JavaDuration(config.kafka.topology.aggregationTimeWindowSize))
            .grace(duration2JavaDuration(config.kafka.topology.aggregationTimeWindowGrace))
        )
        .count
        .suppress(Suppressed.untilWindowCloses(BufferConfig.unbounded()))
        .groupBy((k, v) => (k.window.start.toString, Airport(k.key, v)))
        .aggregate(topDepartureAirportAggregator.initializer)(topDepartureAirportAggregator.adder, topDepartureAirportAggregator.subtractor)
        .toStream
        .to(config.kafka.topology.topDepartureAirportTopic)
      (streamsBuilder, config.kafka.topology.topDepartureAirportTopic)
    }

    def buildTopFlightSpeedStreamsBuilder: (StreamsBuilder, String) = {
      val streamsBuilder           = new StreamsBuilder
      val topSpeedFlightAggregator = new TopSpeedFlightAggregator(config.topElementsAmount)

      streamsBuilder
        .stream[String, FlightReceived](config.kafka.topology.flightReceivedTopic)
        .groupByKey
        .windowedBy(
          TimeWindows
            .of(duration2JavaDuration(config.kafka.topology.aggregationTimeWindowSize))
            .grace(duration2JavaDuration(config.kafka.topology.aggregationTimeWindowGrace))
        )
        .reduce((_, v2) => v2)
        .suppress(Suppressed.untilWindowCloses(BufferConfig.unbounded()))
        .groupBy((k, v) => (k.window.start.toString, SpeedFlight(k.key, v.speed)))
        .aggregate(topSpeedFlightAggregator.initializer)(topSpeedFlightAggregator.adder, topSpeedFlightAggregator.subtractor)
        .toStream
        .to(config.kafka.topology.topSpeedTopic)
      (streamsBuilder, config.kafka.topology.topSpeedTopic)
    }

    def buildTopAirlineStreamsBuilder: (StreamsBuilder, String) = {
      val streamsBuilder       = new StreamsBuilder
      val topAirlineAggregator = new TopAirlineAggregator(config.topElementsAmount)

      streamsBuilder
        .stream[String, FlightReceived](config.kafka.topology.flightReceivedTopic)
        .groupBy((_, v) => v.airline.nameAirline)
        .windowedBy(
          TimeWindows
            .of(duration2JavaDuration(config.kafka.topology.aggregationTimeWindowSize))
            .grace(duration2JavaDuration(config.kafka.topology.aggregationTimeWindowGrace))
        )
        .count
        .suppress(Suppressed.untilWindowCloses(BufferConfig.unbounded()))
        .groupBy((k, v) => (k.window.start.toString, Airline(k.key, v)))
        .aggregate(topAirlineAggregator.initializer)(topAirlineAggregator.adder, topAirlineAggregator.subtractor)
        .toStream
        .to(config.kafka.topology.topAirlineTopic)
      (streamsBuilder, config.kafka.topology.topAirlineTopic)
    }

    def buildTotalFlightsStreamsBuilder: (StreamsBuilder, String) = {
      val streamsBuilder = new StreamsBuilder

      streamsBuilder
        .stream[String, FlightReceived](config.kafka.topology.flightReceivedTopic)
        .groupBy((_, _) => AllRecordsKey)
        .windowedBy(
          TimeWindows
            .of(duration2JavaDuration(config.kafka.topology.aggregationTotalTimeWindowSize))
            .grace(duration2JavaDuration(config.kafka.topology.aggregationTimeWindowGrace))
        )
        .aggregate(FlightNumberList())((_, v, agg) => FlightNumberList(agg.elements :+ v.icaoNumber))
        .suppress(Suppressed.untilWindowCloses(BufferConfig.unbounded()))
        .toStream
        .map((k, v) => (k.window.start.toString, CountFlight(k.window.start.toString, v.elements.distinct.size)))
        .to(config.kafka.topology.totalFlightTopic)
      (streamsBuilder, config.kafka.topology.totalFlightTopic)
    }

    def buildTotalAirlinesStreamsBuilder: (StreamsBuilder, String) = {
      val streamsBuilder = new StreamsBuilder

      streamsBuilder
        .stream[String, FlightReceived](config.kafka.topology.flightReceivedTopic)
        .groupBy((_, _) => AllRecordsKey)
        .windowedBy(
          TimeWindows
            .of(duration2JavaDuration(config.kafka.topology.aggregationTotalTimeWindowSize))
            .grace(duration2JavaDuration(config.kafka.topology.aggregationTimeWindowGrace))
        )
        .aggregate(CodeAirlineList())((_, v, agg) => CodeAirlineList(agg.elements :+ v.airline.codeAirline))
        .suppress(Suppressed.untilWindowCloses(BufferConfig.unbounded()))
        .toStream
        .map((k, v) => (k.window.start.toString, CountAirline(k.window.start.toString, v.elements.distinct.size)))
        .to(config.kafka.topology.totalAirlineTopic)
      (streamsBuilder, config.kafka.topology.totalAirlineTopic)
    }

    val props = new Properties()
    props.put(StreamsConfig.TOPOLOGY_OPTIMIZATION, StreamsConfig.OPTIMIZE)
    List(
      buildFlightReceivedStreamsBuilder,
      flightReceivedListStreamsBuilder,
      buildTopArrivalStreamsBuilder,
      buildTopDepartureStreamsBuilder,
      buildTopAirlineStreamsBuilder,
      buildTopFlightSpeedStreamsBuilder,
      buildTotalFlightsStreamsBuilder,
      buildTotalAirlinesStreamsBuilder
    ).map {
      case (stream, name) =>
        val kafkaStreamsProperties = streamProperties(config.kafka)
        kafkaStreamsProperties.put(StreamsConfig.APPLICATION_ID_CONFIG, s"${config.kafka.applicationId}-$name")
        (stream.build(props), kafkaStreamsProperties)
    }
  }

  private def flightRawToAirportEnrichment(
      flightRawStream: KStream[String, FlightRaw],
      airportRawTable: GlobalKTable[String, AirportRaw]
  ): KStream[String, FlightWithAllAirportInfo] =
    flightRawStream
      .join(airportRawTable)(
        (_, v) => v.departure.iataCode,
        (flightRaw, airportRaw) =>
          FlightWithDepartureAirportInfo(
            flightRaw.flight.iataNumber,
            flightRaw.flight.icaoNumber,
            GeographyInfo(
              flightRaw.geography.latitude,
              flightRaw.geography.longitude,
              flightRaw.geography.altitude,
              flightRaw.geography.direction
            ),
            flightRaw.speed.horizontal,
            AirportInfo(
              airportRaw.codeIataAirport,
              airportRaw.nameAirport,
              airportRaw.nameCountry,
              airportRaw.codeIso2Country,
              airportRaw.timezone,
              airportRaw.gmt
            ),
            flightRaw.arrival.iataCode,
            flightRaw.airline.icaoCode,
            flightRaw.aircraft.regNumber,
            flightRaw.status,
            flightRaw.system.updated
          )
      )
      .join(airportRawTable)(
        (_, v) => v.codeAirportArrival,
        (flightReceivedOnlyDeparture, airportRaw) =>
          FlightWithAllAirportInfo(
            flightReceivedOnlyDeparture.iataNumber,
            flightReceivedOnlyDeparture.icaoNumber,
            flightReceivedOnlyDeparture.geography,
            flightReceivedOnlyDeparture.speed,
            flightReceivedOnlyDeparture.airportDeparture,
            AirportInfo(
              airportRaw.codeIataAirport,
              airportRaw.nameAirport,
              airportRaw.nameCountry,
              airportRaw.codeIso2Country,
              airportRaw.timezone,
              airportRaw.gmt
            ),
            flightReceivedOnlyDeparture.airlineCode,
            flightReceivedOnlyDeparture.airplaneRegNumber,
            flightReceivedOnlyDeparture.status,
            flightReceivedOnlyDeparture.updated
          )
      )

  private def flightWithAirportToAirlineEnrichment(
      flightWithAllAirportStream: KStream[String, FlightWithAllAirportInfo],
      airlineRawTable: GlobalKTable[String, AirlineRaw]
  ): KStream[String, FlightWithAirline] =
    flightWithAllAirportStream
      .join(airlineRawTable)(
        (_, v) => v.airlineCode,
        (flightAndAirport, airlineRaw) =>
          FlightWithAirline(
            flightAndAirport.iataNumber,
            flightAndAirport.icaoNumber,
            flightAndAirport.geography,
            flightAndAirport.speed,
            flightAndAirport.airportDeparture,
            flightAndAirport.airportArrival,
            AirlineInfo(airlineRaw.codeIcaoAirline, airlineRaw.nameAirline, airlineRaw.sizeAirline),
            flightAndAirport.airplaneRegNumber,
            flightAndAirport.status,
            flightAndAirport.updated
          )
      )

  private def flightWithAirportAndAirlineToAirplaneEnrichment(
      flightWithAirline: KStream[String, FlightWithAirline],
      airplaneRawTable: GlobalKTable[String, AirplaneRaw]
  ): KStream[String, FlightReceived] =
    flightWithAirline
      .leftJoin(airplaneRawTable)(
        (_, v) => v.airplaneRegNumber,
        (flightAndAirline, airplaneRaw) =>
          FlightReceived(
            flightAndAirline.iataNumber,
            flightAndAirline.icaoNumber,
            flightAndAirline.geography,
            flightAndAirline.speed,
            flightAndAirline.airportDeparture,
            flightAndAirline.airportArrival,
            flightAndAirline.airline,
            airplaneInfoOrDefault(airplaneRaw),
            flightAndAirline.status,
            flightAndAirline.updated
          )
      )

  private def airplaneInfoOrDefault(airplaneRaw: AirplaneRaw): AirplaneInfo =
    Option(airplaneRaw)
      .map(airplane => AirplaneInfo(airplane.numberRegistration, airplane.productionLine, airplane.modelCode))
      .getOrElse(AirplaneInfo("N/A", "N/A", "N/A"))

}