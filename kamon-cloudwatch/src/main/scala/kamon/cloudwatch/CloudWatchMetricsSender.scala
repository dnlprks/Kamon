package kamon.cloudwatch

import java.util.Date

import akka.actor.{ Actor, Props }
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.{ Protocol, ClientConfiguration }
import com.amazonaws.regions.{ Regions, Region }
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClient
import com.amazonaws.services.cloudwatch.model._
import kamon.metric.SubscriptionsDispatcher.TickMetricSnapshot
import kamon.metric.instrument._

import scala.collection.JavaConverters._

class CloudWatchMetricsSender() extends Actor {

  val config = context.system.settings.config.getConfig("kamon.cloudwatch")

  val namespace = config.getString("name-space")
  val batchSize = config.getInt("batch-size")
  val dimensions = None :: config.getObject("dimensions").asScala.map(dim ⇒ {
    val name = dim._1
    val value = dim._2.unwrapped.toString
    Some(new Dimension().withName(name).withValue(value))
  }).toList

  // TODO: Look to use the Akka dispatcher ExecutorService
  val client = new AmazonCloudWatchAsyncClient(new DefaultAWSCredentialsProviderChain(),
    config.getString("proxy.host") match {
      case proxy: String if proxy.length > 0 ⇒
        new ClientConfiguration().withProxyHost(proxy).withProxyPort(config.getInt("proxy.port")).withProtocol(Protocol.HTTP)
      case _ ⇒ new ClientConfiguration()
    })
  client.setRegion(Region.getRegion(Regions.fromName(config.getString("region"))))

  def receive = {
    case tick: TickMetricSnapshot ⇒ writeMetricsToRemote(tick)
  }

  def writeMetricsToRemote(tick: TickMetricSnapshot): Unit = {
    val timestamp = new Date()
    val data = for {
      configDimension ← dimensions
      (groupIdentity, groupSnapshot) ← tick.metrics
      groupDimension = new Dimension().withName(groupIdentity.category).withValue(groupIdentity.name)
      groupDimensions = (groupDimension :: configDimension.toList).asJava
      (metricIdentity, metricSnapshot) ← groupSnapshot.metrics
      datum = new MetricDatum().withDimensions(groupDimensions).withMetricName(metricIdentity.name).withTimestamp(timestamp)
    } yield {
      val (unit, correctionFactor) = metricIdentity.unitOfMeasurement match {
        case Time.Nanoseconds  ⇒ StandardUnit.Microseconds -> 1E-3
        case Time.Microseconds ⇒ StandardUnit.Microseconds -> 1.0
        case Time.Milliseconds ⇒ StandardUnit.Milliseconds -> 1.0
        case Time.Seconds      ⇒ StandardUnit.Seconds -> 1.0
        case Memory.Bytes      ⇒ StandardUnit.Bytes -> 1.0
        case Memory.KiloBytes  ⇒ StandardUnit.Kilobytes -> 1.0
        case Memory.MegaBytes  ⇒ StandardUnit.Megabytes -> 1.0
        case Memory.GigaBytes  ⇒ StandardUnit.Gigabytes -> 1.0
        case _                 ⇒ StandardUnit.Count -> 1.0
      }
      metricSnapshot match {
        case hs: Histogram.Snapshot if hs.numberOfMeasurements > 0 ⇒
          val statSet = new StatisticSet()
            .withMaximum(hs.max.toDouble * correctionFactor)
            .withMinimum(hs.min.toDouble * correctionFactor)
            .withSampleCount(hs.numberOfMeasurements.toDouble)
            .withSum(hs.sum.toDouble * correctionFactor)
          Seq(datum.withStatisticValues(statSet).withUnit(unit))

        case cs: Counter.Snapshot ⇒
          Seq(datum.withValue(cs.count.toDouble * correctionFactor).withUnit(unit))

        case _ ⇒ Seq.empty
      }
    }

    data.flatten.sliding(batchSize, batchSize) foreach { batch ⇒
      client.putMetricDataAsync(new PutMetricDataRequest().withNamespace(namespace).withMetricData(batch.toSeq.asJava))
    }
  }
}

object CloudWatchMetricsSender {
  def props(): Props = Props(new CloudWatchMetricsSender())
}
