package be.doeraene.services.connectedclients

import be.doeraene.websocket.TypedWsChannelActor
import communication.webrtc.WebRTCCommProtocol
import communication.{ComputerMessage, PhoneMessage}
import data.images.ImageData
import data.movie.Movie

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class ConnectedClientsService(using castor.Context, cask.util.Logger) {

  private val movieEditors: AtomicReference[Map[
    ConnectedClientsService.Ids.MovieEditorClientId,
    ConnectedClientsService.MovieEditorClientInfo
  ]] = AtomicReference(Map.empty)
  private val imageProviders: AtomicReference[Map[
    ConnectedClientsService.Ids.ImageProviderClientId,
    ConnectedClientsService.ImageProviderClientInfo
  ]] = AtomicReference(Map.empty)

  private def newConnection(
      movieId: Movie.Id,
      rawChannel: cask.WsChannelActor
  ): ConnectedClientsService.MovieEditorClientInfo =
    synchronized {
      val currentIds: Set[Int] = movieEditors.get.keySet.map(_.value.split("-").last.toInt)
      val nextId               = currentIds.maxOption match {
        case None      => 1
        case Some(max) => (1 to max).find(!currentIds.contains(_)).getOrElse(max + 1)
      }
      val editorId = ConnectedClientsService.Ids.MovieEditorClientId.fromValue(s"editor-$nextId")
      val editor   = ConnectedClientsService.MovieEditorClientInfo(
        editorId,
        TypedWsChannelActor(rawChannel),
        movieId
      )
      movieEditors.updateAndGet(_ + (editorId -> editor))
      editor
    }

  def movieEditorConnects(movieId: Movie.Id, rawChannel: cask.WsChannelActor): cask.WsActor = {
    val movieEditor = newConnection(movieId, rawChannel)

    val id = movieEditor.id

    movieEditors.updateAndGet(_ + (id -> movieEditor))
    movieEditor.channel.send(ComputerMessage.ThisIsYourId(id.value))

    movieEditor.channel.actor(
      {
        case ComputerMessage.WebRTCToServerWrapper(message) =>
          val providerId = ConnectedClientsService.Ids.ImageProviderClientId.fromValue(message.recipientId)
          imageProviders
            .get()
            .get(providerId)
            .map(_.channel)
            .foreach(_.send(PhoneMessage.WebRTCToPhoneWrapper(message.forward(id.value))))
          None
        case ComputerMessage.AskPicture(phoneId) =>
          imageProviders.get().get(ConnectedClientsService.Ids.ImageProviderClientId.fromValue(phoneId)) match {
            case None =>
              println(s"No phone with id $phoneId")
            case Some(imageProvider) =>
              imageProvider.channel.send(PhoneMessage.ComputerAskedPicture(id.value, movieId))
          }
          None
      },
      { case cask.Ws.Close(_, _) =>
        movieEditors.updateAndGet(_ - movieEditor.id)
        ()
      }
    )
  }

  def imageProviderConnects(
      editorId: ConnectedClientsService.Ids.MovieEditorClientId,
      rawChannel: cask.WsChannelActor
  ): Either[String, cask.WsActor] = {
    movieEditors.get.get(editorId).toRight(s"No Movie Editor with id $editorId").map { movieEditor =>
      val imageProvider = ConnectedClientsService.ImageProviderClientInfo(
        ConnectedClientsService.Ids.ImageProviderClientId.newId(),
        TypedWsChannelActor(rawChannel),
        movieEditor.movie,
        movieEditor.id
      )

      imageProviders.updateAndGet(_ + (imageProvider.id -> imageProvider))

      imageProvider.channel.send(
        PhoneMessage.WebRTCToPhoneWrapper(WebRTCCommProtocol.ForwardAskOffer(movieEditor.id.value))
      )

      imageProvider.channel.actor(
        {
          case PhoneMessage.WebRTCToServerWrapper(message) =>
            val movieEditorId = ConnectedClientsService.Ids.MovieEditorClientId.fromValue(message.recipientId)
            movieEditors
              .get()
              .get(movieEditorId)
              .foreach(_.channel.send(ComputerMessage.WebRTCToComputerWrapper(message.forward(imageProvider.id.value))))
            None
          case PhoneMessage.HeartBeat => None
        },
        { case cask.Ws.Close(_, _) =>
          imageProviders.updateAndGet(_ - imageProvider.id)
          ()
        }
      )
    }
  }

}

object ConnectedClientsService {

  object Ids {
    opaque type MovieEditorClientId = String

    object MovieEditorClientId {
      def newId(): MovieEditorClientId = UUID.randomUUID().toString

      inline def fromValue(value: String): MovieEditorClientId = value

      extension (id: MovieEditorClientId) {
        inline def value: String = id
      }
    }

    opaque type ImageProviderClientId = String

    object ImageProviderClientId {
      def newId(): ImageProviderClientId = UUID.randomUUID().toString

      inline def fromValue(value: String): ImageProviderClientId = value

      extension (id: ImageProviderClientId) {
        inline def value: String = id
      }
    }
  }

  case class MovieEditorClientInfo(
      id: Ids.MovieEditorClientId,
      channel: TypedWsChannelActor[ComputerMessage.ComputerToServerMessage, ComputerMessage.ServerToComputerMessage],
      movie: Movie.Id
  )

  case class ImageProviderClientInfo(
      id: Ids.ImageProviderClientId,
      channel: TypedWsChannelActor[PhoneMessage.PhoneToServerMessage, PhoneMessage.ServerToPhoneMessage],
      movie: Movie.Id,
      connectedTo: Ids.MovieEditorClientId
  )

}
