package converter.domain.rate

object RateExceptions:
  import scala.util.Try

  sealed trait RateException extends Exception
  
  final class ProvidersNotAvailable(exProviderA: Exception, exProviderB: Exception) extends RateException:
    override def getMessage: String = s"Rate providers are not available: ProviderA exception: ${exProviderA.getMessage}; ProviderB exception: ${exProviderB.getMessage}"

  final class Unexpected(unexpected: Try[Any]) extends RateException:
    override def getMessage: String = unexpected.fold(ex => s"Unexpected failure: $ex", value => s"Unexpected successful response: $value")

  sealed trait InputErrorException extends RateException
  final class CurrencyNotSupported(currency: String) extends InputErrorException:
    override def getMessage: String = s"Currency $currency is not supported."

  final class DateNotSupported(date: String, detail: String) extends InputErrorException:
    override def getMessage: String = s"Failed to parse the date $date into LocalDate; The format is not supported. Details: $detail"