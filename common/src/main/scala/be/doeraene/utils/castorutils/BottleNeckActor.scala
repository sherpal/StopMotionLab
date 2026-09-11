package be.doeraene.utils.castorutils

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

/** Creates an actor to which you can send messages destined to the underlying actor.
  *
  * Only one message is allowed to be sent at the time, until a reply comes back.
  *
  * Note: this itself is not an actor (in the sense that it does not extend [[castor.Actor]]), but it is a wrapper
  * around an actor that exposes an API to interact with it.
  */
class BottleNeckActor[Message](underlying: castor.Actor[Message])(using ct: ClassTag[Message])(using castor.Context) {
  override def toString: String =
    s"BottleNeckActor[${ct.runtimeClass.getName}](${hashCode().toHexString})"

  private val child = BottleNeckActor.BottleNeckChild[Message](underlying)

  /** Send the `command` to the underlying actor, if a command is not currently inflight
    */
  def passIfPossible[Reply](command: castor.Actor[Reply] => Message): Future[Option[Reply]] =
    child.ask[Option[Reply]](BottleNeckActor.BottleNeckMessage.IncomingMessage(_, command))

  /** Force sending the `command` to the underlying actor, retrying until the command can pass through.
    */
  def forcePass[Reply](command: castor.Actor[Reply] => Message, maxRetries: Int = Int.MaxValue): Future[Reply] =
    passIfPossible(command).flatMap {
      case None =>
        if maxRetries > 0 then forcePass(command, maxRetries = maxRetries - 1)
        else Future.failed(RuntimeException("No more retry for sending the message to underlying actor."))
      case Some(reply) => Future.successful(reply)
    }
}

object BottleNeckActor {
  private sealed trait BottleNeckMessage[Message, Reply]
  private object BottleNeckMessage:
    case class IncomingMessage[M, R](replyTo: castor.Actor[Option[R]], command: castor.Actor[R] => M)
        extends BottleNeckMessage[M, R] {
      def ask(entity: castor.Actor[M])(using castor.Context): Future[Unit] =
        entity.ask(command).map { reply =>
          replyTo.send(Some(reply))
          ()
        }
    }
    case class ResponseSent[M]() extends BottleNeckMessage[M, ?]

  private class BottleNeckChild[Message](underlying: castor.Actor[Message])(using ct: ClassTag[Message])(using
      castor.Context
  ) extends castor.StateMachineActor[BottleNeckMessage[Message, ?]] {
    override def toString: String =
      s"BottleNeckActor[${ct.runtimeClass.getName}](${hashCode().toHexString})"

    override def initialState: State = Idle()

    private case class InFlight()
        extends State({
          case BottleNeckMessage.ResponseSent()              => Idle()
          case BottleNeckMessage.IncomingMessage(replyTo, _) =>
            replyTo.send(Option.empty)
            state
        })

    private case class Idle()
        extends State({
          case BottleNeckMessage.ResponseSent()                                   => state // should not happen
          case incoming: BottleNeckMessage.IncomingMessage[Message @unchecked, ?] =>
            incoming.ask(underlying).onComplete {
              case Success(()) =>
                send(BottleNeckMessage.ResponseSent())
              case Failure(exception) =>
                exception.printStackTrace() // todo: better logging
                send(BottleNeckMessage.ResponseSent())
            }
            InFlight()
        })
  }

}
