package data.movie

import castorwire.Bridge
import data.images.ImageData
import eventsourcing.{Effect, EntityInformation}
import io.circe.{Codec, Decoder, Encoder}
import urldsl.errors.DummyError
import urldsl.errors.DummyError.dummyError
import urldsl.vocabulary.{FromString, Printer}

case class Movie(
    id: Movie.Id,
    name: String,
    images: Vector[Movie.ImageDataWithOrdering],
    createdAt: Long,
    deletedAt: Long
) {
  def created: Boolean = createdAt > 0L
  def deleted: Boolean = deletedAt > 0L
  def active: Boolean  = created && !deleted

  def lastIndex: Option[Int] = images.flatMap(_.maybeIndex).maxOption

  def containsImage(imageId: ImageData.Id, atIndex: Int): Boolean =
    images.exists(info => info.imageData.id == imageId && info.maybeIndex.contains[Int](atIndex))

  def sortedImages: Vector[ImageData] = images.sorted.collect { case Movie.ImageDataWithOrdering(imageData, Some(_)) =>
    imageData
  }
}

object Movie {
  given Codec[Movie] = io.circe.generic.semiauto.deriveCodec

  opaque type Id = Int

  object Id {
    given Codec[Id] = Codec.from(Decoder.decodeInt, Encoder.encodeInt)

    def apply(value: Int): Id = value

    def dummy: Id = -1

    extension (id: Id) {
      def value: Int = id
    }
  }

  given FromString[Id, DummyError] = _.toIntOption.toRight(dummyError)
  given Printer[Id]                = _.toString

  case class ImageDataWithOrdering(imageData: ImageData, maybeIndex: Option[Int]) derives Codec {
    def mapIndex(mapping: Option[Int] => Option[Int]): ImageDataWithOrdering =
      copy(maybeIndex = mapping(maybeIndex))
  }
  object ImageDataWithOrdering {
    given Ordering[ImageDataWithOrdering] = Ordering.by(_.maybeIndex)
  }

  enum Command:
    case Create(replyTo: castor.Actor[Option[Movie]])
    case ChangeName(newName: String, replyTo: castor.Actor[Boolean])
    case Get(replyTo: castor.Actor[Option[Movie]])
    case Delete(replyTo: castor.Actor[Boolean])
    case RawGet(replyTo: castor.Actor[Movie])
    case AddImage(imageId: ImageData.Id, replyTo: castor.Actor[Boolean])
    case RemoveImages(toRemove: Vector[(ImageData.Id, Int)], replyTo: castor.Actor[Boolean])
    case DuplicateImages(toDuplicate: Vector[(ImageData.Id, Int)], replyTo: castor.Actor[Boolean])
    case MoveImageRange(direction: MoveDirection, minIndex: Int, maxIndex: Int, replyTo: castor.Actor[Boolean])

    def handle(state: Movie, id: Int): Effect[Event, Movie] = {
      def now(): Long = System.currentTimeMillis() / 1000
      this match {
        case Create(replyTo) =>
          if state.created then
            // already created, tell them and don't do anything
            Effect.Ignore().thenReply(replyTo)(_ => Option.empty)
          else Effect.Persist(Event.Created(Id(id), now())).thenReply(replyTo)(Some(_))
        case ChangeName(newName, replyTo) =>
          if state.active then Effect.Persist(Event.NameChanged(newName)).thenReply(replyTo)(_ => true)
          else Effect.Ignore().thenReply(replyTo)(_ => false)
        case Get(replyTo) =>
          Effect.ReplyTo(replyTo, movie => Option.when(movie.active)(movie))
        case Delete(replyTo) =>
          if state.active then Effect.Persist(Event.Deleted(now())).thenReply(replyTo)(_ => true)
          else Effect.ReplyTo(replyTo, _ => false)
        case RawGet(replyTo) =>
          Effect.ReplyTo(replyTo, identity)
        case AddImage(imageId, replyTo) =>
          if state.active then
            Effect.Persist(Event.ImageAdded(imageId, state.lastIndex.getOrElse(-1) + 1)).thenReply(replyTo)(_ => true)
          else Effect.ReplyTo(replyTo, _ => false)
        case RemoveImages(toRemove, replyTo) =>
          if state.active && toRemove.forall(state.containsImage) then
            Effect.Persist(Event.ImagesRemoved(toRemove)).thenReply(replyTo)(_ => true)
          else Effect.ReplyTo(replyTo, _ => false)
        case DuplicateImages(toDuplicate, replyTo) =>
          if state.active && toDuplicate.forall(state.containsImage) then
            Effect.Persist(Event.ImagesDuplicated(toDuplicate)).thenReply(replyTo)(_ => true)
          else Effect.ReplyTo(replyTo, _ => false)
        case MoveImageRange(direction, minIndex, maxIndex, replyTo) =>
          if state.active && minIndex >= 0 && minIndex <= maxIndex && state.images
              .flatMap(_.maybeIndex)
              .exists(_ >= maxIndex) && (direction match {
              case MoveDirection.Left  => minIndex > 0
              case MoveDirection.Right => state.images.flatMap(_.maybeIndex).exists(_ > maxIndex)
            })
          then Effect.Persist(Event.RangeMoved(direction, minIndex, maxIndex)).thenReply(replyTo)(_ => true)
          else Effect.ReplyTo(replyTo, _ => false)
      }
    }

  object Command {

    /** Not `derives Codec`: the `replyTo: castor.Actor[R]` fields need a live [[Bridge]] (one per connection) to become
      * wire-safe, and that bridge only exists once a client has actually connected -- long after this companion object
      * is compiled. So the codec is a plain method, explicitly parameterized by whichever connection's bridge is
      * relevant right now, instead of a `given` resolved once for everyone. See `castorwire.Bridge.bridgedCodec` for
      * how the `castor.Actor[R]` fields themselves are handled, with no change to this ADT beyond this method.
      */
    def codec(using Bridge): Codec[Command] = Bridge.bridgedCodec[Command]
  }

  enum Event derives Codec:
    case Created(id: Id, at: Long)
    case NameChanged(newName: String)
    case Deleted(at: Long)
    case ImageAdded(imageId: ImageData.Id, atIndex: Int)
    case ImagesRemoved(toRemove: Vector[(ImageData.Id, Int)])
    case ImagesDuplicated(toDuplicate: Vector[(ImageData.Id, Int)])
    case RangeMoved(direction: MoveDirection, minIndex: Int, maxIndex: Int)

    def apply(movie: Movie): Movie = this match {
      case Created(id, at) =>
        movie.copy(
          id = id,
          createdAt = at
        )
      case NameChanged(newName) =>
        movie.copy(name = newName)
      case Deleted(at) =>
        movie.copy(deletedAt = at)
      case ImageAdded(imageId, atIndex) =>
        val data      = Movie.ImageDataWithOrdering(ImageData(imageId), Some(atIndex))
        val newImages = movie.images :+ data
        movie.copy(images = newImages)
      case ImagesRemoved(toRemove) =>
        val removedIndices = toRemove.map(_._2)

        val mapMaybeIndex: Option[Int] => Option[Int] = {
          case None                                          => None
          case Some(index) if removedIndices.contains(index) => None
          case Some(index)                                   =>
            Some(index - removedIndices.count(_ < index))
        }
        val imagesModified = movie.images.map(_.mapIndex(mapMaybeIndex))
        movie.copy(images = imagesModified)
      case ImagesDuplicated(toDuplicate) =>
        val (deletedImages, stillThere) = movie.images.partitionMap {
          case ImageDataWithOrdering(imageData, None)        => Left(imageData)
          case ImageDataWithOrdering(imageData, Some(index)) => Right(imageData -> index)
        }

        val toDuplicateSet = toDuplicate.toSet

        val withDuplicated = stillThere
          .sortBy(_._2)
          .flatMap((imageData, index) =>
            if toDuplicateSet.contains((imageData.id, index)) then Vector(imageData, imageData)
            else Vector(imageData)
          )

        val newImages = deletedImages.map(ImageDataWithOrdering(_, None)) ++ withDuplicated.zipWithIndex.map(
          (image, index) => ImageDataWithOrdering(image, Some(index))
        )

        movie.copy(images = newImages)
      case RangeMoved(direction, minIndex, maxIndex) =>
        val indexMapping: Option[Int] => Option[Int] = direction match {
          case MoveDirection.Left => {
            case None                                 => None
            case Some(index) if index == minIndex - 1 => Some(maxIndex)
            case Some(index) if index > maxIndex      => Some(index)
            case Some(index) if index < minIndex      => Some(index)
            case Some(index)                          => Some(index - 1)
          }
          case MoveDirection.Right => {
            case None                                 => None
            case Some(index) if index == maxIndex + 1 => Some(minIndex)
            case Some(index) if index < minIndex      => Some(index)
            case Some(index) if index > maxIndex      => Some(index)
            case Some(index)                          => Some(index + 1)
          }
        }

        movie.copy(images = movie.images.map(_.mapIndex(indexMapping)))
    }

  val entityInfo: EntityInformation[Command, Event, Movie] =
    EntityInformation.usingCirceSerialization[Movie.Command, Movie.Event, Movie](
      Movie(Movie.Id.dummy, "Untitled", Vector.empty, createdAt = 0L, deletedAt = 0L),
      _(_),
      (command, state, id) => command.handle(state, id)
    )

  enum MoveDirection derives Codec:
    case Left, Right

}
