package eventsourcing

import scalasql.simple.DbApi

import java.time.temporal.ChronoUnit

private[eventsourcing] class Supervisor(
    db: DbApi,
    config: EventSourcingService.Config
)(using castor.Context)
    extends castor.StateMachineActor[Supervisor.SupervisorMessage] {
  import Supervisor.*

  private class TheState(entities: Map[(id: Int, entityType: String), EntitySlot])
      extends State({
        case commandEnvelope @ EntityCommand(id, entityInfo, command) =>
          val entityType = entityInfo.entityKind.name
          entities.get((id, entityType)) match {
            case None =>
              val actor =
                EventSourcedActor(
                  id,
                  entityInfo,
                  db,
                  config,
                  castor.ProxyActor[EntityIsNowPassive, SupervisorMessage](identity, this)
                )
              actor.send(EventSourcedActor.ActorCommand.LoadNext())
              actor.send(EventSourcedActor.ActorCommand.Wrapper(command))

              val trimmedEntities = if entities.size >= config.maxInMemoryEntities then {
                val liveEntities = entities.toVector.collect { case (value, slot: EntitySlot.Live) => (value, slot) }
                liveEntities.minByOption(_._2.lastAccessed) match {
                  case Some(toPassivate) =>
                    toPassivate._2.actor.send(EventSourcedActor.ActorCommand.Passivate())
                    entities.updated(toPassivate._1, EntitySlot.Passivating(Vector.empty))
                  case None => entities
                }
              } else entities

              TheState(
                trimmedEntities + ((id = id, entityType = entityType) -> EntitySlot.Live(
                  actor = actor.asInstanceOf[castor.Actor[eventsourcing.EventSourcedActor.ActorCommand[?]]],
                  lastAccessed = System.currentTimeMillis()
                ))
              )
            case Some(EntitySlot.Live(actor, _)) =>
              actor.send(EventSourcedActor.ActorCommand.Wrapper(command))
              TheState(entities.updated((id, entityType), EntitySlot.Live(actor, System.currentTimeMillis())))
            case Some(EntitySlot.Passivating(commands)) =>
              TheState(entities.updated((id, entityType), EntitySlot.Passivating(commands :+ commandEnvelope)))
          }
        case EntityIsNowPassive(id, entityType) =>
          entities.get((id, entityType)) match {
            case None                                   => ()
            case Some(EntitySlot.Live(_, _))            => ()
            case Some(EntitySlot.Passivating(commands)) =>
              commands.foreach(send) // sending to self the waiting commands
          }
          TheState(entities.removed((id, entityType)))
        case CheckIdleEntities() =>
          val (idleEntities, activeEntities) = entities.partitionMap {
            case (id, passivating: EntitySlot.Passivating)     => Right((id, passivating))
            case (id, live @ EntitySlot.Live(_, lastAccessed)) =>
              Either.cond(
                (System.currentTimeMillis() - lastAccessed) <= config.entityIdleShutdownTime.toMillis,
                (id, live),
                (id, live)
              )
          }

          idleEntities.map(_._2.actor).foreach(_.send(EventSourcedActor.ActorCommand.Passivate()))
          scheduleCheckIdleEntities()
          TheState(activeEntities.toMap ++ idleEntities.map((id, _) => (id, EntitySlot.Passivating(Vector.empty))))
      })

  override def initialState: State = TheState(Map.empty)

  private def scheduleCheckIdleEntities(): Unit = summon[castor.Context]
    .scheduleMsg(
      this,
      Supervisor.CheckIdleEntities(),
      java.time.Duration.of(config.entityIdleShutdownTime.toMillis / 2, ChronoUnit.MILLIS)
    )

  if config.removeIdleEntities then scheduleCheckIdleEntities()
}

object Supervisor {
  private enum EntitySlot:
    case Live(actor: castor.Actor[EventSourcedActor.ActorCommand[?]], lastAccessed: Long)
    case Passivating(bufferedCommands: Vector[EntityCommand[?, ?, ?]])

  private[eventsourcing] sealed trait SupervisorMessage

  private[eventsourcing] case class EntityCommand[Command, Event, State](
      id: Int,
      entityInfo: EntityInformation[Command, Event, State],
      command: Command
  ) extends SupervisorMessage

  private[eventsourcing] case class EntityIsNowPassive(id: Int, entityType: String) extends SupervisorMessage

  private case class CheckIdleEntities() extends SupervisorMessage

}
