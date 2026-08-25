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

  def movieEditorConnects(movieId: Movie.Id, rawChannel: cask.WsChannelActor): cask.WsActor = {
    val movieEditor = ConnectedClientsService.MovieEditorClientInfo(
      ConnectedClientsService.Ids.MovieEditorClientId.newId(),
      TypedWsChannelActor(rawChannel),
      movieId
    )
    val id = movieEditor.id

    movieEditors.updateAndGet(_ + (id -> movieEditor))
    movieEditor.channel.send(ComputerMessage.ThisIsYourId(id.value))

    movieEditor.channel.actor(
      {
        case ComputerMessage.WebRTCToServerWrapper(message) =>
          val providerId = ConnectedClientsService.Ids.ImageProviderClientId.fromUUID(message.recipientId)
          imageProviders
            .get()
            .get(providerId)
            .map(_.channel)
            .foreach(_.send(PhoneMessage.WebRTCToPhoneWrapper(message.forward(id.value))))
          None
        case ComputerMessage.AskPicture(phoneId) =>
          imageProviders.get().get(ConnectedClientsService.Ids.ImageProviderClientId.fromUUID(phoneId)) match {
            case None =>
              println(s"No phone with id $phoneId")
            case Some(imageProvider) =>
              imageProvider.channel.send(PhoneMessage.ComputerAskedPicture(id.value))
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
            val movieEditorId = ConnectedClientsService.Ids.MovieEditorClientId.fromUUID(message.recipientId)
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

  def imageUploaded(movie: Movie.Id, recipient: UUID, imageData: ImageData): Boolean = {
    val movieEditorId = ConnectedClientsService.Ids.MovieEditorClientId.fromUUID(recipient)
    movieEditors.get.get(movieEditorId) match {
      case None              => false
      case Some(movieEditor) =>
        movieEditor.channel.send(ComputerMessage.PictureData(imageData.id))
        true
    }
  }

}

object ConnectedClientsService {

  object Ids {
    opaque type MovieEditorClientId = UUID

    object MovieEditorClientId {
      def newId(): MovieEditorClientId = UUID.randomUUID()

      inline def fromUUID(uuid: UUID): MovieEditorClientId = uuid

      extension (id: MovieEditorClientId) {
        inline def value: UUID = id
      }
    }

    opaque type ImageProviderClientId = UUID

    object ImageProviderClientId {
      def newId(): ImageProviderClientId = UUID.randomUUID()

      inline def fromUUID(uuid: UUID): ImageProviderClientId = uuid

      extension (id: ImageProviderClientId) {
        inline def value: UUID = id
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
