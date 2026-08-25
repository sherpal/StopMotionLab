package be.doeraene.routes

import be.doeraene.services.connectedclients.ConnectedClientsService
import data.movie.Movie

import java.util.UUID

//noinspection TypeAnnotation
class VideoFluxRoutes(using connectedClientsService: ConnectedClientsService)(using castor.Context, cask.util.Logger)
    extends cask.Routes {

  @cask.websocket("/ws/image-provider-connection/:editorId")
  def phone(editorId: String) = {
    val typedEditorId = ConnectedClientsService.Ids.MovieEditorClientId.fromUUID(UUID.fromString(editorId))
    cask.WsHandler { underlying =>
      connectedClientsService.imageProviderConnects(typedEditorId, underlying) match {
        case Left(err) =>
          underlying.send(cask.Ws.Close(code = 404, reason = err))
          cask.WsActor { case _ =>
            ()
          }
        case Right(actor) => actor
      }
    }
  }

  @cask.websocket("/ws/movie-editor-connection/:movieId")
  def computer(movieId: Int) = cask.WsHandler { underlying =>
    connectedClientsService.movieEditorConnects(Movie.Id(movieId), underlying)
  }

  initialize()

}
