package converter

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import akka.http.scaladsl.testkit.RouteTestTimeout
import scala.concurrent.duration._

import akka.actor.typed.{ActorRef, Behavior}

import converter.domain.convertibleMessage.Convertible.TradeMessage
import rateProviders.{RateProviderProtocol, RateProviderA, RateProviderB}
import rateProviders.RateProviderCommon.RateProviders

class ConversionRoutesSpec extends AnyWordSpec with Matchers with ScalaFutures with ScalatestRouteTest:

  // the Akka HTTP route testkit does not yet support a typed actor system (https://github.com/akka/akka-http/issues/2036)
  // so we have to adapt for now
  lazy val testKit = ActorTestKit()
  implicit def typedSystem: ActorSystem[_] = testKit.system
  override def createActorSystem(): akka.actor.ActorSystem =
    testKit.system.classicSystem

  override def afterAll(): Unit = 
    testKit.shutdownTestKit()

  implicit val routeTestTimeout: RouteTestTimeout = RouteTestTimeout(20.seconds)
  
  val rateProviderA: ActorRef[RateProviderProtocol.Command] = testKit.spawn(RateProviderA.apply(), "rateProviderA")
  val rateProviderB: ActorRef[RateProviderProtocol.Command] = testKit.spawn(RateProviderB.apply(), "rateProviderB")
  implicit val rateProviders: RateProviders = RateProviders(rateProviderA, rateProviderB)

  implicit val rateRegistryActor: ActorRef[RateRegistry.Command] = testKit.spawn(RateRegistry.apply(), "rateRegistry")        
  val converterActor = testKit.spawn(Converter(), "Converter")
  
  lazy val routes = new ConversionRoutes(converterActor).conversionRoutes

  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import JsonFormats._

  case class TradeDetails (marketId: Int, selectionId: Int, odds: BigDecimal, date: String, currency: String, stake: BigDecimal)
  val tradeDetails = TradeDetails(
    marketId = 123456, 
    selectionId = 987654, 
    odds = 2.2,
    date = "2021-05-18T21:32:42.324Z",
    currency = "USD",
    stake = 253.67
  )

  "be able to convert trade message (POST /api/v1/conversion/trade)" in {  
    val tradeMessage = TradeMessage(
      tradeDetails.marketId, 
      tradeDetails.selectionId, 
      tradeDetails.odds, 
      stake = 253.67, 
      currency = "USD", 
      date = tradeDetails.date
    )

    val tradeEntity = Marshal(tradeMessage).to[MessageEntity].futureValue
    val request = Post("/api/v1/conversion/trade").withEntity(tradeEntity)

    request ~> routes ~> check {
      status should === (StatusCodes.Created)
      contentType should === (ContentTypes.`application/json`)
      val message = s"""{"currency":"EUR","date":"${tradeDetails.date}","marketId":${tradeDetails.marketId},"odds":${tradeDetails.odds},"selectionId":${tradeDetails.selectionId},"stake":207.54899}"""
      entityAs[String] should === (message)
    }
  }

  "incorrect date format in trade message (POST /api/v1/conversion/trade)" in {
    val notSupportedDate = "1621368156000"
    val tradeMessage = TradeMessage(
      tradeDetails.marketId, 
      tradeDetails.selectionId, 
      tradeDetails.odds, 
      stake = tradeDetails.stake, 
      currency = tradeDetails.currency, 
      date = notSupportedDate
    )

    val tradeEntity = Marshal(tradeMessage).to[MessageEntity].futureValue
    val request = Post("/api/v1/conversion/trade").withEntity(tradeEntity)

    request ~> routes ~> check {
      status should === (StatusCodes.UnprocessableContent)
      contentType should === (ContentTypes.`text/plain(UTF-8)`)
      val message = s"Failed to parse the date $notSupportedDate into LocalDate; The format is not supported. Details: Text '$notSupportedDate' could not be parsed at index 0"
      entityAs[String] should === (message)
    }
  }

  "incorrect currency code in trade message (POST /api/v1/conversion/trade)" in {
    val notSupportedCurrency = "ETH"
    val tradeMessage = TradeMessage(
      tradeDetails.marketId, 
      tradeDetails.selectionId, 
      tradeDetails.odds, 
      stake = tradeDetails.stake, 
      currency = notSupportedCurrency, 
      date = tradeDetails.date
    )

    val tradeEntity = Marshal(tradeMessage).to[MessageEntity].futureValue
    val request = Post("/api/v1/conversion/trade").withEntity(tradeEntity)

    request ~> routes ~> check {
      status should === (StatusCodes.UnprocessableContent)
      contentType should ===(ContentTypes.`text/plain(UTF-8)`)
      val message = s"Currency $notSupportedCurrency is not supported."
      entityAs[String] should === (message)
    }
  }

