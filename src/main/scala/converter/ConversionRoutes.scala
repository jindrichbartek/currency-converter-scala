package converter

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Success, Failure}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Route, ExceptionHandler}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.pattern.StatusReply
import akka.util.Timeout
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._

import domain.convertibleMessage.Convertible.{TradeMessage, ConvertibleMessage}
import JsonFormats.tradeMessageFormat
import domain.rate.RateExceptions.InputErrorException

sealed trait Command
private sealed trait InternalCommand extends Command
private final case class MessageConverted(tradeMessage: ConvertibleMessage) extends InternalCommand
private final case class MessageConversionError(ex: Exception) extends InternalCommand

class ConversionRoutes(converterRoot: ActorRef[Converter.Command])(implicit val system: ActorSystem[_]):
  private implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  private implicit val timeout: Timeout = Config.getTimeout(ActorKey.Converter)
  
  def convert(message: ConvertibleMessage): Future[Command] =
    converterRoot.askWithStatus(Converter.Convert(message, _))
    .map {
      case Converter.RespondConvert(response) => 
        MessageConverted(response)
    }
    .recover {
      case ex: Exception =>
        MessageConversionError(ex)
    }

  val exceptionHandler = ExceptionHandler {
    case ex: Exception =>
      extractUri { uri =>
        complete((StatusCodes.InternalServerError, ex.getMessage))
      } 
  }

  val conversionRoutes: Route =
    handleExceptions(exceptionHandler) {
      pathPrefix("api") {
        concat(
          pathPrefix("v1") {
            concat(
              pathPrefix("conversion") {
                concat(
                  pathPrefix("trade") {
                    concat(
                      pathEnd {
                        concat(
                          get {
                            complete(StatusCodes.OK, "This is the trade conversion data for GET request!")
                          },
                          post {
                            entity(as[TradeMessage]) { request =>                              
                              onSuccess(convert(request)) {
                                case MessageConverted(tradeMessage: TradeMessage) =>
                                  complete((StatusCodes.Created, tradeMessage))
                                case MessageConverted(unexpected) =>
                                  complete((StatusCodes.InternalServerError, s"Unknown input message: $unexpected"))
                                case MessageConversionError(ex: InputErrorException) => 
                                  complete((StatusCodes.UnprocessableEntity, ex.getMessage))
                                case MessageConversionError(ex: Exception) => 
                                  complete((StatusCodes.InternalServerError, ex.getMessage))
                              }
                            }
                          })
                    })
                })
            })
        })
      }
    }
