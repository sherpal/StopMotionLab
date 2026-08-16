package utils.webrtc

import com.raquo.laminar.api.A.*
import communication.webrtc.*
import org.scalajs.dom
import org.scalajs.dom.{
  MediaStream,
  MediaStreamConstraints,
  MediaStreamTrack,
  RTCConfiguration,
  RTCIceCandidate,
  RTCIceCandidateInit,
  RTCPeerConnection,
  RTCSdpType,
  RTCSessionDescription,
  RTCSessionDescriptionInit
}

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.util.{Failure, Success}

/** Wrapper class around the things that need to be done to establish a webrtc connection.
  */
trait WebRTCConnection(using ExecutionContext) {

  protected lazy val pc = RTCPeerConnection(new RTCConfiguration {
    iceServers = js.Array()
  })

  extension (pc: RTCPeerConnection) {
    protected def addTrack(track: MediaStreamTrack, stream: MediaStream): Unit =
      pc.asInstanceOf[js.Dynamic].applyDynamic("addTrack")(track, stream)
  }

  protected def init(): Future[Unit] = {
    pc.onicecandidate = { event =>
      val candidate = event.candidate
      if !js.isUndefined(candidate) && candidate != null then {
        val onIceCandidateEvent = OnIceCandidateEvent(
          candidate.candidate,
          candidate.sdpMid,
          candidate.sdpMLineIndex
        )
        outWriter.onNext(WebRTCCommProtocol.SendOnIceCandidateEvent(onIceCandidateEvent, recipientId))
      }
    }

    Future.successful(())
  }

  protected def addIceCandidate(event: OnIceCandidateEvent): Unit = {
    if remoteDescriptionSet then
      pc.addIceCandidate(RTCIceCandidate(new RTCIceCandidateInit {
        candidate = event.candidate
        sdpMid = event.sdpMid
        sdpMLineIndex = event.sdpMLineIndex
      }))
    else queuedOnIceCandidates += event
  }

  protected def addIceCandidates(events: Iterable[OnIceCandidateEvent]): Unit =
    events.foreach(addIceCandidate)

  protected var remoteDescriptionSet         = false
  private var queuedOnIceCandidates          = Set.empty[OnIceCandidateEvent]
  protected def flushOnIceCandidates(): Unit = {
    addIceCandidates(queuedOnIceCandidates)
    queuedOnIceCandidates = Set.empty
  }

  protected def outWriter: Observer[WebRTCCommProtocol.SendOnIceCandidateEvent]

  protected def recipientId: java.util.UUID

}

object WebRTCConnection {

  class Consumer(
      protected val outWriter: Observer[WebRTCCommProtocol.ConsumerToServer],
      providerId: java.util.UUID
  )(using ExecutionContext)
      extends WebRTCConnection {
    protected def recipientId: java.util.UUID = providerId

    def videoStreamSignal: Signal[Option[MediaStream]] = videoStreamVar.signal

    val inMessageObserver: Observer[WebRTCCommProtocol.ServerToConsumer] = Observer {
      case WebRTCCommProtocol.ForwardOffer(offer, _) =>
        println(s"received offer $offer")
        for {
          _ <- pc.setRemoteDescription(new RTCSessionDescription(new RTCSessionDescriptionInit {
            `type` = RTCSdpType.offer
            sdp = offer.sdp
          }))
          answer <- pc.createAnswer().toFuture
          _      <- pc.setLocalDescription(answer).toFuture
        } yield {
          outWriter.onNext(WebRTCCommProtocol.SendAnswer(Answer(answer.sdp), providerId))
          remoteDescriptionSet = true
          flushOnIceCandidates()
          println("coucou les amis")
        }
      case WebRTCCommProtocol.ForwardOnIceCandidateEvent(event, _) =>
        addIceCandidate(event)
      case WebRTCCommProtocol.OfferClosed(_) =>
        videoStreamVar.set(None)
    }.filter(_.id == providerId)

    override def init(): Future[Unit] = for {
      _ <- super.init()
    } yield {
      pc.oniceconnectionstatechange = _ => println(s"WebRTC state: ${pc.iceConnectionState}")

      pc.ontrack = { event =>
        println("Received track event")
        dom.console.log(event)

        val streams = event.asInstanceOf[js.Dynamic].selectDynamic("streams").asInstanceOf[js.Array[MediaStream]]
        streams.headOption match {
          case None         => println("streams was empty")
          case Some(stream) => videoStreamVar.set(Some(stream))
        }
      }
      ()
    }

    private val videoStreamVar = Var(Option.empty[MediaStream])

  }

  class Provider(
      stream: MediaStream,
      protected val outWriter: Observer[WebRTCCommProtocol.ProviderToServer],
      consumerId: java.util.UUID
  )(using ExecutionContext)
      extends WebRTCConnection {
    protected def recipientId: java.util.UUID = consumerId

    val inMessagesObserver: Observer[WebRTCCommProtocol.ServerToProvider] = Observer {
      case WebRTCCommProtocol.ForwardAskOffer(_)                   => // nothing to do here
      case WebRTCCommProtocol.ForwardOnIceCandidateEvent(event, _) =>
        addIceCandidate(event)
      case WebRTCCommProtocol.ForwardAnswer(answer, _) =>
        pc.setRemoteDescription(RTCSessionDescription(new RTCSessionDescriptionInit {
          `type` = RTCSdpType.answer
          sdp = answer.sdp
        }))
          .toFuture
          .onComplete {
            case Failure(exception) => throw exception
            case Success(())        =>
              remoteDescriptionSet = true
              flushOnIceCandidates()
          }
    }.filter(_.id == consumerId)

    override def init(): Future[Unit] = for {
      _ <- Future.successful(outWriter.onNext(WebRTCCommProtocol.WillSendOffer(consumerId)))
      _ <- super.init()
      _ = stream.getTracks().foreach { track =>
        pc.addTrack(track, stream)
      }
      offer <- pc.createOffer().toFuture
      _     <- pc.setLocalDescription(offer).toFuture
      _ = outWriter.onNext(WebRTCCommProtocol.SendOffer(Offer(offer.sdp), consumerId))
    } yield ()

  }

}
