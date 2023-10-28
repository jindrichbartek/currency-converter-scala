package converter

import akka.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration

private val AppName = "converter-app"

enum ActorKey(val key: String):
  case Converter extends ActorKey(s"$AppName.converter")
  case RateRegistry extends ActorKey(s"$AppName.rate-registry")
  case RateProviders extends ActorKey(s"$AppName.rate-providers")

object Config:
  private val config = ConfigFactory.load()
  
  def getTimeout(actorKey: ActorKey): Timeout =
    val javaDuration = config.getDuration(s"${actorKey.key}.ask-timeout")
    Timeout(Duration.fromNanos(javaDuration.toNanos))

  def getDefaultTargetCurrency(): String =
    config.getString(s"$AppName.default-target-currency")

  def getAProviderApiKey(): String = 
    config.getString(s"$AppName.rate-providers.provider-a-api-key")
  
  def getBProviderApiKey(): String = 
    config.getString(s"$AppName.rate-providers.provider-b-api-key")

  def getCacheTtlDuration(): FiniteDuration =
    val javaDuration = config.getDuration(s"$AppName.rate-registry.cache-ttl-ms")
    Duration.fromNanos(javaDuration.toNanos)
