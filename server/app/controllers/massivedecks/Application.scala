package controllers.massivedecks

import javax.inject.{Inject, Named}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.mvc._

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout

import controllers.massivedecks.game.Actions.Lobby
import controllers.massivedecks.game.Actions.Lobby.GetLobby
import controllers.massivedecks.game.Actions.Player.Formatters._
import controllers.massivedecks.game.Actions.Player.{GetHand, NewPlayer}
import controllers.massivedecks.game.Actions.Store.{LobbyAction, NewLobby, PlayerAction}
import controllers.massivedecks.game.NotFoundException
import models.massivedecks.Player.{Id, Secret}

class Application @Inject() (@Named("store") store: ActorRef)(implicit ec: ExecutionContext) extends Controller {
  implicit val timeout: Timeout = 15.seconds // Should be more than the CardCast API timeout.

  def index() = Action {
    Ok(views.html.massivedecks.index())
  }

  def createLobby() = Action.async {
    resultOrError(store ? NewLobby)
  }

  def notifications(lobbyId: String) = WebSocket.acceptWithActor[String, String] {
    request => out => NotificationHandler.props(lobbyId, store, out)
  }

  def getLobby(id: String) = Action.async {
    resultOrError(store ? LobbyAction(id, GetLobby))
  }

  def command(lobbyId: String) = Action.async(parse.json) { request: Request[JsValue] =>
    Lobby.Action(request.body) match {
      case Some(action) =>
        resultOrError(store ? LobbyAction(lobbyId, action))

      case None =>
        Future.successful(BadRequest("Invalid command."))
    }
  }

  def newPlayer(lobbyId: String) = Action.async(parse.json) { request: Request[JsValue] =>
    request.body.validate[NewPlayer].asOpt match {
      case Some(action) =>
        resultOrError(store ? PlayerAction(lobbyId, action))

      case None =>
        Future.successful(BadRequest("Badly formed name provided."))
    }
  }

  def getPlayer(lobbyId: String, playerId: Int) = Action.async(parse.json) { request: Request[JsValue] =>
    (request.body \ "secret").validate[String].asOpt match {
      case Some(secret) =>
        resultOrError(store ? PlayerAction(lobbyId, GetHand(Secret(Id(playerId), secret))))

      case None =>
        Future.successful(BadRequest("Badly formed secret provided."))
    }
  }

  private def resultOrError(response: Future[Any]): Future[Result] = {
    val result: Future[Try[JsValue]] = response.mapTo[Try[JsValue]]
    result.map({
      case Success(json) =>
        Ok(json).as(JSON)

      case Failure(error) =>
        if (error.isInstanceOf[IllegalArgumentException] ||
          error.isInstanceOf[IllegalStateException]) {
          BadRequest(error.getMessage).as(TEXT)
        } else if (error.isInstanceOf[NotFoundException]) {
          NotFound
        } else {
          throw error
        }
    })
  }
}
