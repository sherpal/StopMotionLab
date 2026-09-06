package be.doeraene.utils.castorutils

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

/** Creates an actor to which you can send messages destined to the underlying actor.
  *
  * Only one message is allowed to be sent at the time, until a reply comes back.
  */
class BottleNeckActor[Message](underlying: castor.Actor[Message])(using ct: ClassTag[Message])(using castor.Context)
    extends castor.SimpleActor[BottleNeckActor.BottleNeckActorMessage[?, Message]]() {
  override def toString: String =
    s"BottleNeckActor[${ct.runtimeClass.getName}](${hashCode().toHexString})"

  private var inflight: Boolean = false

  override def run(msg: BottleNeckActor.BottleNeckActorMessage[?, Message]): Unit =
    if inflight then msg.replyTo.send(Option.empty)
    else {
      inflight = true
      msg.ask(underlying).onComplete {
        case Failure(exception) =>
          // todo: better logging
          exception.printStackTrace()
          inflight = false
        case Success(()) =>
          inflight = false
      }
    }

  /** Send the `command` to the underlying actor, if a command is not currently inflight
    */
  def passIfPossible[Reply](command: castor.Actor[Reply] => Message): Future[Option[Reply]] =
    this.ask[Option[Reply]](BottleNeckActor.BottleNeckActorMessage(_, command))

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
  case class BottleNeckActorMessage[Reply, Message](
      replyTo: castor.Actor[Option[Reply]],
      command: castor.Actor[Reply] => Message
  ) {
    def ask(entity: castor.Actor[Message])(using castor.Context): Future[Unit] =
      entity.ask(command).map { reply =>
        replyTo.send(Some(reply))
        ()
      }
  }

}
