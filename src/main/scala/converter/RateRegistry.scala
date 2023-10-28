package converter

object RateRegistry:
    
  import java.time.LocalDate
  import akka.event.slf4j.Logger
  import akka.http.scaladsl.model.DateTime
  import akka.pattern.StatusReply
  import akka.actor.typed.scaladsl.{Behaviors, ActorContext}
  import akka.actor.typed.{ActorRef, Behavior}
  import scala.util.{Success, Failure}
  import akka.util.Timeout
  import akka.actor.typed.scaladsl.TimerScheduler

  import rateProviders.{RateProviderProtocol, RateProviderA, RateProviderB}
  import rateProviders.RateProviderCommon.RateProviders
  import domain.currency.Currency
  import domain.rate.Rate.CurrencyDateParam
  import domain.rate.Rate.ExchangeRate
  import domain.rate.RateExceptions.{RateException, ProvidersNotAvailable, Unexpected}
  import domain.rate.RateInputParser
  
  sealed trait Command
  // downstream from Converter
  final case class RequestRate(currency: String, date: String, replyTo: ActorRef[StatusReply[RateResponse]]) extends Command 
 
  // internal
  private sealed trait InternalCommand extends Command
  private final case class RateFetched(currencyDate: CurrencyDateParam, rate: BigDecimal) extends InternalCommand  
  private final case class RateFetchError(currencyDate: CurrencyDateParam, reason: Exception) extends InternalCommand
  private final case class AskFallbackProvider(currencyDateParam: CurrencyDateParam, exProviderA: Exception) extends InternalCommand
  private final case class RateToInvalidate(currencyDateParam: CurrencyDateParam) extends InternalCommand

  // upstream to Converter
  sealed trait Response
  final case class RateResponse(rate: BigDecimal) extends Response 

  final case class ConverterActors(actors: List[ActorRef[StatusReply[RateResponse]]])

  def apply()(implicit 
      providers: RateProviders
  ): Behavior[Command] = 
    Behaviors.withTimers {
      implicit timers =>
        setupRateRegistry(Map.empty, Map.empty)
    }

  private def setupRateRegistry(
      cache: Map[CurrencyDateParam, ExchangeRate],
      waitingActorsMap: Map[CurrencyDateParam, ConverterActors]
  )(implicit 
      providers: RateProviders,
      timers: TimerScheduler[Command]
  ): Behavior[Command] = 
    Behaviors.receive { (context, message) =>
      implicit val ctx: ActorContext[Command] = context
      val log = context.log
      message match {
        case RequestRate(currency, date, replyTo) =>
          // If a currency or date is not supported, return one of the RateExceptions
          RateInputParser.parse(currency, date) match {
            case Right(currencyDateParam) =>
              cache.get(currencyDateParam) match {
                // The rate has been found
                case Some(rateExchange: ExchangeRate) =>
                  log.info(s"CacheHit() for $currencyDateParam")
                  replyTo ! StatusReply.success(RateResponse(rateExchange.rateVal))
                  Behaviors.same
                // The rate has not been found           
                case None =>
                  log.info(s"Cache miss for $currencyDateParam; waitingActors: ${numberOfWaitingActors(waitingActorsMap, currencyDateParam)}")                             
                  fetchOnce(currencyDateParam, waitingActorsMap, replyTo)
                  val newActors = addActorToWaitingMap(replyTo, waitingActorsMap, currencyDateParam)
                  setupRateRegistry(cache, newActors)
              }
            case Left(rateException: RateException) => 
              replyTo ! StatusReply.error(rateException)
              Behaviors.same
          }
        case RateFetched(currencyDateParam, rateVal) =>
          handleRateFetched(currencyDateParam, rateVal, cache, waitingActorsMap)
        case RateFetchError(currencyDateParam, reason) => 
          handleRateFetchError(currencyDateParam, reason, cache, waitingActorsMap)
        case AskFallbackProvider(currencyDateParam, exProviderA) =>
          askFallbackProvider(currencyDateParam, exProviderA)
        // Remove the rate from the cache
        case RateToInvalidate(currencyDateParam: CurrencyDateParam) =>
          log.info(s"RateToInvalidate() is received. Invalidating the rate for: $currencyDateParam...")
          val reducedCache = cache - currencyDateParam
          log.info(s"Cache size after invalidation: ${reducedCache.size}")
          setupRateRegistry(reducedCache, waitingActorsMap)
      }
  }

  private def addActorToWaitingMap(
      replyTo: ActorRef[StatusReply[RateResponse]], 
      waitingActorsMap: Map[CurrencyDateParam, ConverterActors], 
      currencyDateParam: CurrencyDateParam
  ): Map[CurrencyDateParam, ConverterActors] =
    val currentActors = waitingActorsMap.getOrElse(currencyDateParam, ConverterActors(List.empty)).actors
    val newActors = waitingActorsMap + (currencyDateParam -> ConverterActors(currentActors :+ replyTo))
    newActors
  
  private def numberOfWaitingActors(
      waitingActorsMap: Map[CurrencyDateParam, ConverterActors], 
      currencyDateParam: CurrencyDateParam
  ): Int =
    waitingActorsMap.getOrElse(currencyDateParam, ConverterActors(List.empty)).actors.size 

  private def handleRateFetched(
      currencyDateParam: CurrencyDateParam, 
      rateVal: BigDecimal, 
      cache: Map[CurrencyDateParam, ExchangeRate], 
      waitingActorsMap: Map[CurrencyDateParam, ConverterActors]
  )(implicit 
      context: ActorContext[Command],
      timers: TimerScheduler[Command],
      providers: RateProviders
  ): Behavior[Command] = 
      val log = context.log
      log.info("Rate fetched for {}", currencyDateParam.currency.toString)
      val emptyConvertersSet = ConverterActors(List.empty)
      val waitingActors = waitingActorsMap.getOrElse(currencyDateParam, emptyConvertersSet).actors
      // Notify all waiting actors   
      waitingActors.foreach(_ ! StatusReply.success(RateResponse(rateVal)))

      // Start timer to invalidate the rate after the cache TTL is expired
      timers.startSingleTimer(currencyDateParam, RateToInvalidate(currencyDateParam), Config.getCacheTtlDuration())

      // Save the fetched rate to the cache
      val newRate = ExchangeRate(currencyDateParam.currency.toString, rateVal)
      val updatedCache = cache + (currencyDateParam -> newRate)
      log.info(s"Rate for $currencyDateParam is saved to the cache")
      log.info(s"New cache size: ${updatedCache.size}")
      setupRateRegistry(updatedCache, waitingActorsMap - currencyDateParam)

  private def handleRateFetchError(
      currencyDateParam: CurrencyDateParam, 
      ex: Exception, 
      cache: Map[CurrencyDateParam, ExchangeRate], 
      waitingActorsMap: Map[CurrencyDateParam, ConverterActors]
  )(implicit 
      context: ActorContext[Command],
      timers: TimerScheduler[Command],
      providers: RateProviders
  ): Behavior[Command] =
      val log = context.log
      log.info("Rate fetch failed: {}", ex.getMessage)
      val emptyActorsSet = ConverterActors(List.empty)
      val waitingActors = waitingActorsMap.getOrElse(currencyDateParam, emptyActorsSet).actors
      waitingActors.foreach(_ ! StatusReply.error(ex))
      setupRateRegistry(cache, waitingActorsMap - currencyDateParam)
  
  private def askFallbackProvider(
      currencyDateParam: CurrencyDateParam, 
      exProviderA: Exception
  )(implicit 
      context: ActorContext[Command],
      providers: RateProviders
  ): Behavior[Command] =
    implicit val timeout: Timeout = Config.getTimeout(ActorKey.RateProviders)
    context.askWithStatus(providers.rateProviderB, RateProviderProtocol.FetchRate(currencyDateParam, _)) {
      case Success(RateProviderProtocol.RatesSuccess(value)) => RateFetched(currencyDateParam, value)
      case Failure(exProviderB: Exception) => 
        RateFetchError(currencyDateParam, ProvidersNotAvailable(exProviderA, exProviderB))
      case unexpected => RateFetchError(currencyDateParam, ProvidersNotAvailable(exProviderA, Unexpected(unexpected)))
    }
    Behaviors.same

  private def fetchOnce(
      currencyDateParam: CurrencyDateParam,
      waitingActorsMap: Map[CurrencyDateParam, ConverterActors],
      replyTo: ActorRef[StatusReply[RateResponse]]
  )(implicit 
      context: ActorContext[Command], 
      providers: RateProviders
  ): Unit = 
    implicit val timeout: Timeout = Config.getTimeout(ActorKey.RateProviders)

    val waitingActorsCount = waitingActorsMap.getOrElse(currencyDateParam, ConverterActors(List.empty)).actors.size
    // if there are no actors waiting yet - we must iniate the rate fetch for the first time
    if waitingActorsCount == 0 then
      context.askWithStatus(providers.rateProviderA, RateProviderProtocol.FetchRate(currencyDateParam, _)) {
        case Success(RateProviderProtocol.RatesSuccess(value)) => RateFetched(currencyDateParam, value)
        case Failure(exProviderA: Exception) => 
          context.log.warn("fallback provider is called...")
          AskFallbackProvider(currencyDateParam, exProviderA)
        case unexpected =>
          context.log.error(s"Provider has an unexpected error or response: $unexpected")
          AskFallbackProvider(currencyDateParam, Unexpected(unexpected))
      }