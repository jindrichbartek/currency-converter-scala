package converter.rateProviders

import scala.util.{Success, Failure}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, ActorContext}
import akka.actor.typed.scaladsl.adapter._

import akka.pattern.StatusReply

import akka.http.scaladsl.Http
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.sprayJsonUnmarshaller
import akka.http.scaladsl.model.{HttpRequest, ResponseEntity, StatusCodes}
import akka.stream.Materializer

import spray.json._
import DefaultJsonProtocol._

import converter.domain.currency.Currency
import converter.domain.rate.Rate.CurrencyDateParam
import converter.Config
import RateProviderProtocol.{Command, FetchRate, Response, RatesSuccess}

object RateProviderProtocol:
  sealed trait Command
  // downstream from RateRegistry
  final case class FetchRate(currencyDate: CurrencyDateParam, replyTo: ActorRef[StatusReply[Response]]) extends Command
  
  // upstream to RateRegistry
  sealed trait Response
  final case class RatesSuccess(value: BigDecimal) extends Response

object RateProviderCommon:
  final case class RateProviders(
    rateProviderA: ActorRef[RateProviderProtocol.Command], 
    rateProviderB: ActorRef[RateProviderProtocol.Command]
  )

trait RateProviderCommon:
  
  sealed trait InternalCommand extends RateProviderProtocol.Command
  protected final case class RateFetched(value: BigDecimal, replyTo: ActorRef[StatusReply[Response]]) extends InternalCommand
  protected final case class RateFetchingError(currency: String, reason: String, replyTo: ActorRef[StatusReply[Response]]) extends InternalCommand
  
  def setup[T: RootJsonFormat](
      uriBuilder: CurrencyDateParam => String, 
      getRateFunc: (T, CurrencyDateParam) => Option[BigDecimal] 
  ): Behavior[Command] = 
    Behaviors.setup { implicit context =>
      val log = context.log
      Behaviors.receiveMessage {
        case FetchRate(currencyDate: CurrencyDateParam, replyTo) =>
          log.info(s"Fetching rate for ${currencyDate.currency}")
          fetchRateFromUri[T]( 
            uriBuilder(currencyDate),
            currencyDate,
            getRateFunc,
            replyTo
          )
          Behaviors.same
        case RateFetched(rate, replyTo) =>
          log.info(s"Rate fetched: $rate")
          replyTo ! StatusReply.success(RatesSuccess(rate))
          Behaviors.same
        case RateFetchingError(currency, reason, replyTo) =>
          log.info(s"Rate fetch failed: $reason")
          replyTo ! StatusReply.error(reason)
          Behaviors.same
        case unexpected =>
          log.error(s"Unexpected message: $unexpected")
          Behaviors.unhandled
      }
    } 

  def fetchRateFromUri[T](
      uri: String,
      currencyDate: CurrencyDateParam,  
      getRateFunction: (T, CurrencyDateParam) => Option[BigDecimal],
      replyTo: ActorRef[StatusReply[Response]]
  )(implicit 
      context: ActorContext[Command], 
      unmarshaller: Unmarshaller[ResponseEntity, T]
  ): Unit =
    import context.executionContext
    val classicSystem = context.system.toClassic
    
    implicit val mat: Materializer = Materializer(context.system)
    val responseFuture = Http(classicSystem).singleRequest(HttpRequest(uri = uri))

    responseFuture.onComplete {
      case Success(response) if response.status == StatusCodes.OK =>
        Unmarshal(response.entity).to[T].onComplete {
          case Success(data) =>
            getRateFunction(data, currencyDate) match {
              case Some(rate) => context.self ! RateFetched(rate, replyTo)
              case None => context.self ! RateFetchingError(currencyDate.currency.toString, s"${currencyDate.currency} not found in rateProvider response: $data", replyTo)
            }
          case Failure(ex) => context.self ! RateFetchingError(currencyDate.currency.toString, s"Failed to parse rate for ${currencyDate.currency}. Unmarshalling was not successful.", replyTo)
        }
      case Success(response) => context.self ! RateFetchingError(currencyDate.currency.toString, s"Rate Provider returned error status code: ${response.status}; response: $response", replyTo)
      case Failure(ex) => context.self ! RateFetchingError(currencyDate.currency.toString, s"Failed to make request to Rate Provider: $ex", replyTo) 
    }
  
  def getDefaultTargetCurrency(): String =
    Config.getDefaultTargetCurrency()

object RateProviderA extends RateProviderCommon:

  val API_KEY = Config.getAProviderApiKey()

  private def uriBuilder(    
      currencyDate: CurrencyDateParam
  ): String = 
    val defaultTargetCurrency = getDefaultTargetCurrency()
    // we use convert endpoint and http protocol since both are only available in free version
    s"""http://api.exchangerate.host/convert
      |?from=${currencyDate.currency.toString}
      |&to=$defaultTargetCurrency
      |&date=${currencyDate.date}
      |&access_key=$API_KEY
      |&amount=1""".stripMargin.replaceAll("\n", "")

  private final case class ExchangeRateResponseA(success: Boolean, historical: Boolean, date: String, result: BigDecimal)

  def apply(): Behavior[Command] =
    implicit val rateFormat: RootJsonFormat[ExchangeRateResponseA] = jsonFormat4(ExchangeRateResponseA.apply)
    setup[ExchangeRateResponseA](uriBuilder, (response, _) => Some(response.result))


object RateProviderB extends RateProviderCommon:

  val API_KEY = Config.getBProviderApiKey()

  private def uriBuilder(
      currencyDate: CurrencyDateParam
  ): String =
    val defaultTargetCurrency = getDefaultTargetCurrency()
    // default to-convert currency is EUR - ideally we should specify that in parameter, but that's paid feature
    s"""http://api.exchangeratesapi.io/v1/${currencyDate.date}
      |?access_key=$API_KEY
      |&symbols=${currencyDate.currency}
      |&format=1""".stripMargin.replaceAll("\n", "")
  
  private final case class ExchangeRateResponseB(success: Boolean, base: String, date: String, rates: Map[String, BigDecimal])

  def apply(): Behavior[Command] =
    implicit val rateFormat: RootJsonFormat[ExchangeRateResponseB] = jsonFormat4(ExchangeRateResponseB.apply)
    setup[ExchangeRateResponseB](uriBuilder, (response, currencyDate) => response.rates.get(currencyDate.currency.toString))
