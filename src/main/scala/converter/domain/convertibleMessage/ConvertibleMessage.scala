package converter.domain.convertibleMessage

object Convertible:
  
  trait ConvertibleMessage:
    val currency: String
    val stake: BigDecimal
    val date: String
  
  final case class TradeMessage(
    marketId: Int,
    selectionId: Int,
    odds: BigDecimal,
    stake: BigDecimal, 
    currency: String, 
    date: String) extends ConvertibleMessage