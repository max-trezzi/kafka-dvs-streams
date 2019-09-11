package it.bitrock.kafkaflightstream.streams

import java.util.Properties

import io.confluent.kafka.serializers.{AbstractKafkaAvroSerDeConfig, KafkaAvroDeserializerConfig}
import it.bitrock.kafkaflightstream.model._
import it.bitrock.kafkaflightstream.streams.config.{AppConfig, KafkaConfig}
import org.apache.kafka.clients.consumer.{ConsumerConfig, OffsetResetStrategy}
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.kstream.{GlobalKTable, TimeWindows, Windowed}
import org.apache.kafka.streams.scala.ImplicitConversions._
import org.apache.kafka.streams.scala.kstream.{Grouped, KStream, KTable, Materialized}
import org.apache.kafka.streams.scala.{ByteArrayKeyValueStore, ByteArrayWindowStore, StreamsBuilder}
import org.apache.kafka.streams.{StreamsConfig, Topology}

object Streams {

  // Whether or not to deserialize `SpecificRecord`s when possible
  final val UseSpecificAvroReader   = true
  final val AutoOffsetResetStrategy = OffsetResetStrategy.EARLIEST
  final val AllRecordsKey: String   = "all"

  private final val europeanCountries = Set(
    "AL",
    "AD",
    "AT",
    "BE",
    "BY",
    "BA",
    "BG",
    "CY",
    "HR",
    "DK",
    "EE",
    "FI",
    "FR",
    "DE",
    "GR",
    "IE",
    "IS",
    "IT",
    "XK",
    "LV",
    "LI",
    "LT",
    "LU",
    "MK",
    "MT",
    "MD",
    "MC",
    "ME",
    "NO",
    "NL",
    "PL",
    "PT",
    "GB",
    "CZ",
    "RO",
    "RU",
    "SM",
    "RS",
    "SK",
    "SI",
    "ES",
    "SE",
    "CH",
    "UA",
    "HU",
    "VA"
  )

  def buildTopology(config: AppConfig, kafkaStreamsOptions: KafkaStreamsOptions): Topology = {
    implicit val KeySerde: Serde[String]              = kafkaStreamsOptions.keySerde
    implicit val flightRawSerde: Serde[FlightRaw]     = kafkaStreamsOptions.flightRawSerde
    implicit val airportRawSerde: Serde[AirportRaw]   = kafkaStreamsOptions.airportRawSerde
    implicit val airlineRawSerde: Serde[AirlineRaw]   = kafkaStreamsOptions.airlineRawSerde
    implicit val airplaneRawSerde: Serde[AirplaneRaw] = kafkaStreamsOptions.airplaneRawSerde
    //implicit val cityRawSerde: Serde[CityRaw] = kafkaStreamsOptions.cityRawSerde

    //for join trasformation
    implicit val flightWithDepartureAirportInfoSerde: Serde[FlightWithDepartureAirportInfo] =
      kafkaStreamsOptions.flightWithDepartureAirportInfo
    implicit val flightWithAllAirportSerde: Serde[FlightWithAllAirportInfo] = kafkaStreamsOptions.flightWithAllAirportInfo
    implicit val flightWithAirlineSerde: Serde[FlightWithAirline]           = kafkaStreamsOptions.flightWithAirline

    //output topic
    implicit val flightEnrichedEventSerde: Serde[FlightEnrichedEvent] = kafkaStreamsOptions.flightEnrichedEventSerde
    implicit val topAggregationKeySerde: Serde[Long]                  = kafkaStreamsOptions.topAggregationKeySerde
    implicit val topArrivalAirportListSerde: Serde[TopArrivalAirportList]           = kafkaStreamsOptions.topArrivalAirportListEventSerde
    implicit val topDepartureAirportListSerde: Serde[TopDepartureAirportList]           = kafkaStreamsOptions.topDepartureAirportListEventSerde
    implicit val topAirportSerde: Serde[Airport]                      = kafkaStreamsOptions.topAirportEventSerde

    def buildFlightReceived(
        fligthtRawStream: KStream[String, FlightRaw],
        airportRawTable: GlobalKTable[String, AirportRaw],
        airlineRawTable: GlobalKTable[String, AirlineRaw],
        airplaneRawTable: GlobalKTable[String, AirplaneRaw]
    ): KStream[String, FlightEnrichedEvent] = {

      val flightJoinAirport: KStream[String, FlightWithAllAirportInfo] = flightRawToAirportEnrichment(fligthtRawStream, airportRawTable)

      val flightAirportAirline: KStream[String, FlightWithAirline] =
        flightWithAirportToAirlineEnrichment(flightJoinAirport, airlineRawTable)

      val flightAirportAirlineAirplane: KStream[String, FlightEnrichedEvent] =
        flightWithAirportAndAirlineToAirplaneEnrichment(flightAirportAirline, airplaneRawTable)

      flightAirportAirlineAirplane.to(config.kafka.topology.flightReceivedTopic)
      flightAirportAirlineAirplane
    }


    def buildTop5Arrival(flightEnriched: KStream[String, FlightEnrichedEvent]): Unit = {
      val topArrivalAirportAggregator = new TopArrivalAirportAggregator(config.topElementsAmount)

      implicit val groupedRaw: Grouped[String, FlightEnrichedEvent] = Grouped.`with`(s"${config.kafka.topology.flightReceivedTopic}-arrival")
      implicit val groupedTable: Grouped[String, Airport]           = Grouped.`with`(s"${config.kafka.topology.topArrivalAirportTopic}-table")
      implicit val materialized: Materialized[String, Long, ByteArrayWindowStore] =
        Materialized.as(s"${config.kafka.topology.topArrivalAirportTopic}-count")
      implicit val materializedTable: Materialized[String, TopArrivalAirportList, ByteArrayKeyValueStore] =
        Materialized.as(s"${config.kafka.topology.topArrivalAirportTopic}-table")

      flightEnriched
        .groupBy((_, value) => value.airportArrival.codeAirport)
        .windowedBy(TimeWindows.of(duration2JavaDuration(config.kafka.topology.aggregationTimeWindowSize)))
        .count
        .groupBy((k, v) => (k.window.start.toString, Airport(k.key, v)))
        .aggregate(topArrivalAirportAggregator.initializer)(topArrivalAirportAggregator.adder, topArrivalAirportAggregator.subtractor)
        .toStream
        .to(config.kafka.topology.topArrivalAirportTopic)
    }
    def buildTop5Departure(flightEnriched: KStream[String, FlightEnrichedEvent]): Unit = {
      val topDepartureAirportAggregator = new TopDepartureAirportAggregator(config.topElementsAmount)

      implicit val groupedRaw: Grouped[String, FlightEnrichedEvent] = Grouped.`with`(s"${config.kafka.topology.flightReceivedTopic}-departure")
      implicit val groupedTable: Grouped[String, Airport]           = Grouped.`with`(s"${config.kafka.topology.topDepartureAirportTopic}-table")
      implicit val materialized: Materialized[String, Long, ByteArrayWindowStore] =
        Materialized.as(s"${config.kafka.topology.topDepartureAirportTopic}-count")
      implicit val materializedTable: Materialized[String, TopDepartureAirportList, ByteArrayKeyValueStore] =
        Materialized.as(s"${config.kafka.topology.topDepartureAirportTopic}-table")

      flightEnriched
        .groupBy((_, value) => value.airportDeparture.codeAirport)
        .windowedBy(TimeWindows.of(duration2JavaDuration(config.kafka.topology.aggregationTimeWindowSize)))
        .count
        .groupBy((k, v) => (k.window.start.toString, Airport(k.key, v)))
        .aggregate(topDepartureAirportAggregator.initializer)(topDepartureAirportAggregator.adder, topDepartureAirportAggregator.subtractor)
        .toStream
        .to(config.kafka.topology.topDepartureAirportTopic)
    }

    val streamsBuilder   = new StreamsBuilder
    val flightRawStream  = streamsBuilder.stream[String, FlightRaw](config.kafka.topology.flightRawTopic)
    val airportRawTable  = streamsBuilder.globalTable[String, AirportRaw](config.kafka.topology.airportRawTopic)
    val airlineRawTable  = streamsBuilder.globalTable[String, AirlineRaw](config.kafka.topology.airlineRawTopic)
    val airplaneRawTable = streamsBuilder.globalTable[String, AirplaneRaw](config.kafka.topology.airplaneRawTopic)
    //val cityRawStream  = streamsBuilder.globalTable[String, CityRaw](config.kafka.topology.cityRawTopic)

    val flightReceivedStream = buildFlightReceived(flightRawStream, airportRawTable, airlineRawTable, airplaneRawTable)
    buildTop5Arrival(flightReceivedStream)
    buildTop5Departure(flightReceivedStream)

    streamsBuilder.build
  }

  def streamProperties(config: KafkaConfig): Properties = {
    val props = new Properties
    props.put(StreamsConfig.APPLICATION_ID_CONFIG, config.applicationId)
    props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
    props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, config.topology.cacheMaxSizeBytes.toString)
    props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, config.topology.threadsAmount.toString)
    props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, config.topology.commitInterval.toMillis.toString)
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, AutoOffsetResetStrategy.toString.toLowerCase)
    props.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, config.schemaRegistryUrl.toString)
    props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, UseSpecificAvroReader.toString)

    props
  }

  def flightRawToAirportEnrichment(
      flightRawStream: KStream[String, FlightRaw],
      airportRawTable: GlobalKTable[String, AirportRaw]
  ): KStream[String, FlightWithAllAirportInfo] = {

    flightRawStream
      .join(airportRawTable)(
        (_, value) => value.departure.iataCode,
        (flight, airport) =>
          FlightWithDepartureAirportInfo(
            GeographyInfo(flight.geography.latitude, flight.geography.longitude, flight.geography.altitude, flight.geography.direction),
            flight.speed.horizontal,
            AirportInfo(airport.codeIataAirport, airport.nameAirport, airport.nameCountry, airport.codeIso2Country),
            flight.arrival.iataCode,
            flight.airline.icaoCode,
            flight.aircraft.regNumber,
            flight.status
          )
      )
      .join(airportRawTable)(
        (_, value2) => value2.codeAirportArrival,
        (flightReceivedOnlyDeparture, airport) =>
          FlightWithAllAirportInfo(
            flightReceivedOnlyDeparture.geography,
            flightReceivedOnlyDeparture.speed,
            flightReceivedOnlyDeparture.airportDeparture,
            AirportInfo(airport.codeIataAirport, airport.nameAirport, airport.nameCountry, airport.codeIso2Country),
            flightReceivedOnlyDeparture.airlineCode,
            flightReceivedOnlyDeparture.airplaneRegNumber,
            flightReceivedOnlyDeparture.status
          )
      )
      .filter(
        (_, value) =>
          europeanCountries.contains(value.airportDeparture.codeIso2Country) &&
            europeanCountries.contains(value.airportArrival.codeIso2Country)
      )
  }

  def flightWithAirportToAirlineEnrichment(
      flightWithAllAirportStream: KStream[String, FlightWithAllAirportInfo],
      airlineRawTable: GlobalKTable[String, AirlineRaw]
  ): KStream[String, FlightWithAirline] = {

    flightWithAllAirportStream
      .join(airlineRawTable)(
        (_, value) => value.airlineCode,
        (flightAndAirport, airline) =>
          FlightWithAirline(
            flightAndAirport.geography,
            flightAndAirport.speed,
            flightAndAirport.airportDeparture,
            flightAndAirport.airportArrival,
            AirlineInfo(airline.nameAirline, airline.sizeAirline),
            flightAndAirport.airplaneRegNumber,
            flightAndAirport.status
          )
      )
  }

  def flightWithAirportAndAirlineToAirplaneEnrichment(
      flightWithAirline: KStream[String, FlightWithAirline],
      airplaneRawTable: GlobalKTable[String, AirplaneRaw]
  ): KStream[String, FlightEnrichedEvent] = {
    flightWithAirline
      .leftJoin(airplaneRawTable)(
        (_, value) => value.airplaneRegNumber,
        (flightAndAirline, airplaneTable) =>
          FlightEnrichedEvent(
            flightAndAirline.geography,
            flightAndAirline.speed,
            flightAndAirline.airportDeparture,
            flightAndAirline.airportArrival,
            flightAndAirline.airline,
            Option(airplaneTable).map(airplaneTable => AirplaneInfo(airplaneTable.productionLine, airplaneTable.modelCode)),
            flightAndAirline.status
          )
      )
  }
}
