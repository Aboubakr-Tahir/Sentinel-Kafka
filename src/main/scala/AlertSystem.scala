package sentinel

import java.util.Properties
import java.time.Duration
import org.apache.kafka.streams.scala.StreamsBuilder
import org.apache.kafka.streams.scala.kstream._
import org.apache.kafka.streams.{KafkaStreams, StreamsConfig}
import org.apache.kafka.streams.scala.serialization.Serdes
import org.apache.kafka.streams.kstream.{Windowed, TimeWindows, Suppressed, Produced}
import org.apache.kafka.common.serialization.Serde
import io.circe._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import org.slf4j.LoggerFactory
import scala.util.Try

import org.apache.kafka.streams.scala.ImplicitConversions._
import org.apache.kafka.streams.scala.serialization.Serdes._

// Modèle de données correspondant au JSON du producer.py
case class SystemMetric(timestamp: Double, component_id: String, value: Double, unit: String)

// Nouveau modèle pour les alertes
case class Alert(timestamp: Long, component_id: String, severity: String, message: String)

object AlertSystem extends App {

  val logger = LoggerFactory.getLogger("AlertSystem")

  // Helper pour créer un Serde
  def createSerde[T >: Null](encoder: T => Array[Byte], decoder: Array[Byte] => T): Serde[T] = {
    val serializer = new org.apache.kafka.common.serialization.Serializer[T] {
      override def serialize(topic: String, data: T): Array[Byte] = 
        if (data == null) null else encoder(data)
    }
    val deserializer = new org.apache.kafka.common.serialization.Deserializer[T] {
        override def deserialize(topic: String, data: Array[Byte]): T = {
          if (data == null) return null
          try {
            decoder(data)
          } catch {
            case _: Exception => null
          }
        }
    }
    org.apache.kafka.common.serialization.Serdes.serdeFrom(serializer, deserializer)
  }

  implicit val systemMetricSerde: Serde[SystemMetric] = createSerde[SystemMetric](
    data => data.asJson.noSpaces.getBytes("UTF-8"),
    bytes => decode[SystemMetric](new String(bytes, "UTF-8")).getOrElse(null)
  )

  implicit val alertSerde: Serde[Alert] = createSerde[Alert](
    data => data.asJson.noSpaces.getBytes("UTF-8"),
    bytes => decode[Alert](new String(bytes, "UTF-8")).getOrElse(null)
  )

  val props = new Properties()
  props.put(StreamsConfig.APPLICATION_ID_CONFIG, "sentinel-alert-system-v2")
  props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
  // La sérialisation par défaut pour les clés (String pour component_id) et valeurs (String pour le JSON)
  props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.stringSerde.getClass)
  props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.stringSerde.getClass)
  
  // Conf pour lire depuis le début du topic si aucun offset n'est trouvé
  props.put(org.apache.kafka.clients.consumer.ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

  // Gestion du commit : Pour se rapprocher du "commit manuel" après traitement, 
  // on réduit l'intervalle de commit, ou on utilise exactly_once_v2 pour garantir le traitement.
  // Kafka Streams gère les offsets automatiquement.
  props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, "100") 
  props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2)

  val builder = new StreamsBuilder()

  // 1. Lire le topic 'system_metrics'
  val metricsStream: KStream[String, String] = builder.stream[String, String]("system_metrics")

  // 2. Parser le JSON en objet SystemMetric
  // On filtre les messages mal formés
  val parsedStream: KStream[String, SystemMetric] = metricsStream.flatMapValues { jsonStr =>
    decode[SystemMetric](jsonStr) match {
      case Right(metric) => Some(metric)
      case Left(error) => 
        logger.error(s"Erreur de parsing JSON: $error pour le message: $jsonStr")
        None
    }
  }

  // --- Logique 1 : Alertes Instantanées (Critique / Santé Serveur) ---
  // On ne regarde que les dangers immédiats qui ne peuvent pas attendre 5 minutes.
  parsedStream.filter { (key, metric) =>
    (metric.component_id == "DISK" && metric.value > 95.0) ||
    (metric.component_id == "RAM" && metric.value > 60.0)
  }
  .map { (key, metric) =>
    val alert = Alert(
      timestamp = System.currentTimeMillis(),
      component_id = metric.component_id,
      severity = "CRITICAL",
      message = s"ALERTE IMMEDIATE [SANTE SERVEUR]: ${metric.component_id} critique à ${metric.value}% !"
    )
    logger.error(alert.message)
    (key, alert)
  }
  .to("system_alerts")(Produced.`with`(org.apache.kafka.common.serialization.Serdes.String(), alertSerde))

  // --- Logique 2 : Anomalies Temporelles (Windowing 5 min) ---
  // Sert à confirmer les surcharges (CPU_TOTAL) ou détecter les bugs (CPU_x)
  
  val groupedStream: KGroupedStream[String, SystemMetric] = parsedStream.groupByKey

  case class AggregateMetric(count: Long, sum: Double, min: Double, max: Double) {
    def average: Double = if (count == 0) 0.0 else sum / count
  }
  
  implicit val aggregateSerde: Serde[AggregateMetric] = createSerde[AggregateMetric](
    data => data.asJson.noSpaces.getBytes("UTF-8"),
    bytes => decode[AggregateMetric](new String(bytes, "UTF-8")).getOrElse(null)
  )

  // Fenêtre de 5 minutes disjointes (Tumbling)
  val windowedStats = groupedStream
    .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofSeconds(40), Duration.ofSeconds(15)))
    .aggregate(AggregateMetric(0L, 0.0, Double.MaxValue, Double.MinValue))(
      (key, newValue, agg) => AggregateMetric(
        count = agg.count + 1,
        sum = agg.sum + newValue.value,
        min = Math.min(agg.min, newValue.value),
        max = Math.max(agg.max, newValue.value)
      )
    )
    .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))

  windowedStats
    .toStream
    .flatMap { (windowedKey, agg) =>
      val key = windowedKey.key()
      val alerts = scala.collection.mutable.ListBuffer[Alert]()
      
      // A. Niveau "Santé Serveur" (Global) : CPU_TOTAL
      if (key == "CPU_TOTAL" && agg.average > 85.0) {
        val alert = Alert(
          timestamp = System.currentTimeMillis(),
          component_id = key,
          severity = "CRITICAL",
          message = s"ALERTE CONFIRMEE [CHARGE GLOBALE]: Le serveur sature (Moyenne CPU_TOTAL: ${agg.average}% sur 5 min)."
        )
        logger.error(alert.message)
        alerts += alert
      }

      // B. Niveau "Comportement Logiciel" (Granulaire) : CPU_0 à CPU_7
      if (key.startsWith("CPU_") && key != "CPU_TOTAL") {
        // Bug applicatif : un cœur sature (boucle infinie ?)
        if (agg.average > 90.0) {
          val alert = Alert(
            timestamp = System.currentTimeMillis(),
            component_id = key,
            severity = "WARNING",
            message = s"DIAGNOSTIC [BUG APPLICATIF]: Le coeur $key est saturé (${agg.average}%). Vérifier les threads."
          )
          logger.warn(alert.message)
          alerts += alert
        }
        // Instabilité (Flapping) sur un cœur spécifique
        if (agg.max - agg.min > 80.0) {
           val alert = Alert(
            timestamp = System.currentTimeMillis(),
            component_id = key,
            severity = "WARNING",
            message = s"DIAGNOSTIC [INSTABILITE]: Le coeur $key oscille violemment (${agg.min}% - ${agg.max}%)."
           )
           logger.warn(alert.message)
           alerts += alert
        }
      }
      alerts.map(a => (key, a))
    }
    .to("system_alerts")(Produced.`with`(org.apache.kafka.common.serialization.Serdes.String(), alertSerde))


  // B. Fuite de Mémoire (Memory Leak) : Augmentation constante
  // On utilise ici aussi suppress pour n'avoir qu'un seul résultat par heure
  groupedStream
    .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofHours(1), Duration.ofSeconds(1)))
    .aggregate(AggregateMetric(0L, 0.0, 0.0, 0.0))(
      (key, newValue, agg) => AggregateMetric(
        count = agg.count + 1,
        sum = agg.sum + newValue.value,
        min = 0, max = 0 // On ignore min/max ici
      )
    )
    .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
    .toStream
    .filter { (windowedKey, agg) => 
      windowedKey.key() == "RAM" && agg.average > 80.0 
    }
    .map { (windowedKey, agg) =>
       val alert = Alert(
          timestamp = System.currentTimeMillis(),
          component_id = windowedKey.key(),
          severity = "WARNING",
          message = s"SUSPICION MEMORY LEAK: Usage RAM moyen élevé (${agg.average}%) sur la dernière heure."
       )
       logger.warn(alert.message)
       (windowedKey.key(), alert)
    }
    .to("system_alerts")(Produced.`with`(org.apache.kafka.common.serialization.Serdes.String(), alertSerde))


  val streams = new KafkaStreams(builder.build(), props)
  
  streams.start()
  
  // Graceful shutdown
  sys.ShutdownHookThread {
    streams.close(Duration.ofSeconds(10))
  }
}
