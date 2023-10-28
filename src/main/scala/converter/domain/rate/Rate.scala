package converter.domain.rate

import java.time.LocalDate
import akka.http.scaladsl.model.DateTime
import converter.domain.currency.Currency
import converter.Config

object Rate:

  sealed trait RateParams
  final case class CurrencyDateParam(currency: Currency, date: LocalDate) extends RateParams
  
  final case class ExchangeRate(currency: String, rateVal: BigDecimal)