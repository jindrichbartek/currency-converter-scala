package converter

import java.util.UUID
import scala.collection.immutable
import scala.concurrent.duration._
import scala.util.{Success, Failure}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, ActorContext}
import akka.http.scaladsl.model.DateTime
import akka.util.Timeout
import akka.pattern.StatusReply

object Converter:
  import java.time.{LocalDate}
  import scala.math.BigDecimal.RoundingMode

  import domain.convertibleMessage.Convertible.{ConvertibleMessage, TradeMessage}
  import domain.rate.RateExceptions

  sealed trait Command
  // downstream from ConversionRoutes
  final case class Convert(message: ConvertibleMessage, replyTo: ActorRef[StatusReply[RespondConvert]]) extends Command
  // internal
  private sealed trait InternalCommand extends Command
  private final case class RateFetched(result: BigDecimal) extends InternalCommand
  private final case class RateError(exc: Exception) extends InternalCommand
    
  // upstream to ConversionRoutes
  sealed trait Response
  final case class RespondConvert(result: ConvertibleMessage) extends Response
  
  def apply()(implicit 
      rateRegistry: ActorRef[RateRegistry.Command]
  ): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match {
        case Convert(convertibleMessage, replyTo) =>
          convertibleMessage match {
            case trade: TradeMessage =>
              val uniqueId = UUID.randomUUID()
              context.spawn(spawnConversionActor(convertibleMessage, replyTo), s"converterWorker_$uniqueId")
              Behaviors.same
          }
        case unexpected =>
          context.log.error(s"Unexpected message: $unexpected")
          Behaviors.unhandled
      }
    }
  
  def spawnConversionActor(
      msg: ConvertibleMessage, 
      replyTo: ActorRef[StatusReply[RespondConvert]]
  )(implicit 
      rateRegistry: ActorRef[RateRegistry.Command]
  ): Behavior[Command] = 
      Behaviors.setup { context =>
        implicit val timeout: Timeout = Config.getTimeout(ActorKey.RateRegistry)

        context.askWithStatus(rateRegistry, RateRegistry.RequestRate(msg.currency, msg.date, _)) {
          case Success(rateResponse: RateRegistry.RateResponse) =>
            RateFetched(rateResponse.rate)
          case Failure(ex: Exception) => 
            context.log.error(s"Failure: ${ex.getMessage}")
            RateError(ex)
          case unexpected => RateError(RateExceptions.Unexpected(unexpected))
        }
        handleRateToConversion(msg, replyTo)
      }

  def handleRateToConversion(
      msg: ConvertibleMessage, 
      replyTo: ActorRef[StatusReply[RespondConvert]]
  ): Behavior[Command] = 
    Behaviors.receive { (context, message) =>
      val log = context.log
      message match {
        case RateFetched(fetchedRate) =>
          val converted = convertWithRate(fetchedRate, msg)
          log.info(s"Converted stake from ${msg.stake} ${msg.currency} into ${converted.stake} ${converted.currency}")
          log.info("Result: {} ", converted)
          replyTo ! StatusReply.success(RespondConvert(converted))
          Behaviors.stopped 
        case RateError(ex) =>
          log.error("RateError: {}", ex.getMessage)
          replyTo ! StatusReply.error(ex)
          Behaviors.stopped
        case unexpected =>
          log.error("Unexpected message: {} ", unexpected)
          Behaviors.unhandled
      }
    }
  
  private def convertWithRate(rate: BigDecimal, originalMsg: ConvertibleMessage): ConvertibleMessage =
    val convertedStake = (originalMsg.stake * rate).setScale(5, RoundingMode.HALF_UP)
    originalMsg match {
      case trade: TradeMessage =>
        trade.copy(stake = convertedStake, currency = Config.getDefaultTargetCurrency())
    }
    
  