package kamon.cloudwatch

import akka.actor._
import akka.event.Logging
import com.typesafe.config.{ ConfigValue, ConfigList }
import kamon.Kamon
import scala.collection.JavaConverters._

object CloudWatch extends ExtensionId[CloudWatchExtension] with ExtensionIdProvider {
  override def lookup(): ExtensionId[_ <: Extension] = CloudWatch
  override def createExtension(system: ExtendedActorSystem): CloudWatchExtension = new CloudWatchExtension(system)
}

class CloudWatchExtension(system: ExtendedActorSystem) extends Kamon.Extension {
  val log = Logging(system, classOf[CloudWatchExtension])
  log.info("Starting the Kamon(CloudWatch) extension")

  private def cloudwatchConfig = system.settings.config.getConfig("kamon.cloudwatch")

  val cloudwatchMetricsListener = system.actorOf(CloudWatchMetricsSender.props(), "cloudwatch-metrics-sender")

  {
    val subscriptions = cloudwatchConfig.getConfig("metric-subscriptions")
    subscriptions.entrySet.asScala.foreach {
      entry ⇒
        {
          val key = entry.getKey
          val patternList = entry.getValue.asInstanceOf[ConfigList]
          patternList.unwrapped().asScala.foreach {
            p ⇒
              {
                val pattern = p.asInstanceOf[String]
                log.info(s"""Subscribing to key "$key" pattern "$pattern"""")
                Kamon.metrics.subscribe(key, pattern, cloudwatchMetricsListener, permanently = true)
              }
          }
        }
    }
  }
}
