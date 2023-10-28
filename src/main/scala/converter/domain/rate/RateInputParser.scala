package converter.domain.rate

import java.time.{Instant, LocalDate, ZoneId, DateTimeException}
import RateExceptions.{CurrencyNotSupported, DateNotSupported, RateException}
import converter.domain.currency.Currency
import Rate.CurrencyDateParam

object RateInputParser:
  
  def parseCurrency(currency: String): Either[CurrencyNotSupported, Currency] =
    Currency.fromString(currency) match {
      case Right(currency) => Right(currency)
      case Left(ex) => Left(ex)
    }

  def parseDate(date: String): Either[DateNotSupported, LocalDate] =
    try {
    val parsedDate = Instant.parse(date).atZone(ZoneId.systemDefault()).toLocalDate
    if (parsedDate.isAfter(LocalDate.now()))
      Left(DateNotSupported(date, "Date is in the future."))
    else
      Right(parsedDate)
    } catch {
      case ex: DateTimeException => Left(DateNotSupported(date, ex.getMessage))
    }

  def parse(currency: String, date: String): Either[RateException, CurrencyDateParam] =
    parseCurrency(currency) match {
      case Right(currency: Currency) =>
        parseDate(date) match {
          case Right(date) => Right(CurrencyDateParam(currency, date))
          case Left(ex) => Left(ex)
        }
      case Left(currencyNotSupported: CurrencyNotSupported) => Left(currencyNotSupported)
    }