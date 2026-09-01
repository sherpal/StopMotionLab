package eventsourcing

import eventsourcing.EventSourcedActor.ActorCommand
import scalasql.simple.{DbApi, SqliteDialect}

private[eventsourcing] class EventSourcedActor[Command, Event, EntityState](
    id: Int,
    entityInfo: EntityInformation[Command, Event, EntityState],
    db: DbApi,
    config: EventSourcingService.Config,
    supervisor: castor.Actor[Supervisor.EntityIsNowPassive]
)(using castor.Context)
    extends castor.StateMachineActor[ActorCommand[Command]] {
  import SqliteDialect.*

  import entityInfo.*

  private def handleEffect(
      lastSequenceNumber: Int,
      currentState: EntityState,
      effect: Effect[Event, EntityState]
  ): (Int, EntityState) = effect match {
    case Effect.Persist(event) =>
      handleEffect(lastSequenceNumber, currentState, Effect.PersistMultiple(Vector(event)))
    case Effect.PersistMultiple(events) =>
      val startingSequenceNumber = lastSequenceNumber + 1

      val nextState = events.foldLeft(currentState)((s, e) => eventHandler(e, s))
      val envelopes = events.zipWithIndex
        .map((event, index) => (event, startingSequenceNumber + index))
        .map((event, sequenceNumber) =>
          EventEnvelope[Event, EntityState](id, sequenceNumber, event, System.currentTimeMillis())
        )
      db.run(
        RawEventEnvelope.insert.values(envelopes.map(encodeEnvelope)*).skipColumns(_.offset)
      )
      (envelopes.map(_.sequenceNumber).maxOption.getOrElse(lastSequenceNumber), nextState)
    case Effect.WithSideEffect(effect, sideEffect) =>
      val resolved = handleEffect(lastSequenceNumber, currentState, effect)
      sideEffect(resolved._2)
      resolved
    case Effect.Ignore() => (lastSequenceNumber, currentState)
  }

  private sealed abstract class TheState(handler: ActorCommand[Command] => State) extends State(handler)
  private case class LoadingState(
      currentSequenceNumber: Int,
      currentState: EntityState,
      eventsInQueue: Vector[ActorCommand[Command]]
  ) extends TheState({
        case ActorCommand.LoadNext() =>
          val nextEnvelopes: Vector[EventEnvelope[Event, EntityState]] = db
            .run(
              RawEventEnvelope.select
                .filter(envelope => envelope.entityId === id && envelope.entityKind === entityKind.name)
                .sortBy(_.sequenceNumber)
                .drop(currentSequenceNumber)
                .take(config.eventPageSize)
            )
            .toVector
            .map(decodeEnvelope)
          if nextEnvelopes.isEmpty then {
            eventsInQueue.foreach(send)
            Loaded(currentSequenceNumber, currentState, passivating = false)
          } else {
            val newState =
              nextEnvelopes.foldLeft(currentState)((state, envelope) => eventHandler(envelope.event, state))
            val nextSequenceNumber = nextEnvelopes.last.sequenceNumber
            send(ActorCommand.LoadNext())
            LoadingState(nextSequenceNumber, newState, eventsInQueue)
          }
        case ActorCommand.Wrapper(command) =>
          LoadingState(currentSequenceNumber, currentState, eventsInQueue :+ ActorCommand.Wrapper(command))
        case ActorCommand.Passivate() =>
          // too soon to passivate, we wait for later
          LoadingState(currentSequenceNumber, currentState, eventsInQueue :+ ActorCommand.Passivate())
      })
  private case class Loaded(lastSequenceNumber: Int, entity: EntityState, passivating: Boolean)
      extends TheState({
        case ActorCommand.LoadNext()       => state
        case ActorCommand.Wrapper(command) =>
          val (nextSequenceNumber, nextState) = handleEffect(
            lastSequenceNumber,
            entity,
            commandHandler(command, entity)
          )
          Loaded(nextSequenceNumber, nextState, passivating)
        case ActorCommand.Passivate() =>
          if !passivating then {
            // need to put back passivate at end of queue. In this state we know this has to be last message now
            send(ActorCommand.Passivate())
            Loaded(lastSequenceNumber, entity, passivating = true)
          } else
            supervisor.send(Supervisor.EntityIsNowPassive(id, entityKind.name))
            Passive()
      })
  private case class Passive()
      extends TheState({ command =>
        println(s"Received this command, weird ($id): $command")
        state
      })

  override def initialState: EventSourcedActor.this.State = LoadingState(0, entityInfo.initialState, Vector.empty)
}

private[eventsourcing] object EventSourcedActor {

  private[eventsourcing] enum ActorCommand[Command]:
    case LoadNext[C]()          extends ActorCommand[C]
    case Wrapper[C](command: C) extends ActorCommand[C]
    case Passivate[C]()         extends ActorCommand[C]

}
