package converter

import spray.json.RootJsonFormat
import spray.json.DefaultJsonProtocol._

import domain.convertibleMessage.Convertible.TradeMessage

object JsonFormats  {
  implicit val tradeMessageFormat: RootJsonFormat[TradeMessage] = jsonFormat6(TradeMessage.apply)
}