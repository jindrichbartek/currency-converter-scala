package converter

import scala.util.{Failure, Success}

import akka.actor.typed.{ActorSystem, ActorRef}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route

import rateProviders.{RateProviderProtocol, RateProviderA, RateProviderB}
import rateProviders.RateProviderCommon.RateProviders

object ConverterApp:
  def startHttpServer(routes: Route)(implicit system: ActorSystem[_]): Unit =
    import system.executionContext

    val futureBinding = Http().newServerAt("localhost", 8080).bind(routes)
    futureBinding.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info("Server online at http://{}:{}/", address.getHostString, address.getPort)
      case Failure(ex) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }

@main def runApp(): Unit =
  val rootBehavior = Behaviors.setup { context =>
    val rateProviderA: ActorRef[RateProviderProtocol.Command] = context.spawn(RateProviderA.apply(), "RateProviderA")
    val rateProviderB: ActorRef[RateProviderProtocol.Command] = context.spawn(RateProviderB.apply(), "RateProviderB")
    implicit val rateProviders: RateProviders = RateProviders(rateProviderA, rateProviderB)
    
    implicit val rateRegistryActor: ActorRef[RateRegistry.Command] = context.spawn(RateRegistry.apply(), "RateRegistry")

    val converterActor = context.spawn(Converter(), "Converter")
    val routes = new ConversionRoutes(converterActor)(context.system)
    ConverterApp.startHttpServer(routes.conversionRoutes)(context.system)

    Behaviors.empty
  }
  val system = ActorSystem(rootBehavior, "ConverterHttpServer")
