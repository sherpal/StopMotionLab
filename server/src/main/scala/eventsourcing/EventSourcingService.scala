package eventsourcing

import scalasql.simple.SqliteDialect

import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class EventSourcingService(
    config: EventSourcingService.Config,
    client: scalasql.DbClient.DataSource,
    isInTest: Boolean = false
)(using
    castor.Context
) {
  import SqliteDialect.*

  private val db = client.getAutoCommitClientConnection

  private val supervisor =
    Supervisor(db, config = config.copy(removeIdleEntities = !isInTest && config.removeIdleEntities))

  def entity[Command, Event, State](id: Int, info: EntityInformation[Command, Event, State]): castor.Actor[Command] =
    castor.ProxyActor[Command, Supervisor.SupervisorMessage](Supervisor.EntityCommand(id, info, _), supervisor)

  /** Returns the biggest entity id for which an event has been registered.
    *
    * If you want to ensure that you don't create an entity with the same id "twice", you should have a "Created" state
    * in your entity, and a first command "Create". That command should then check if the entity is already created, and
    * return a failure if that's the case.
    *
    * @param entityKind
    *   kind of entity for which you want the last id.
    */
  def lastEntityId(entityKind: EntityKind[?, ?]): Option[Int] =
    db.run(RawEventEnvelope.select.filter(_.entityKind === entityKind.name).sortBy(_.entityId).desc.take(1))
      .headOption
      .map(_.entityId)

  /** Registers a [[Projection]] against the events of one entity kind and starts it running immediately. If the log
    * already has events for that kind, they are delivered first, in order, exactly as if they were happening live —
    * there is no separate backfill step.
    *
    * The returned handle only matters for tests (`poke()` forces a catch-up cycle without waiting on the automatic poll
    * timer); production code can otherwise discard it.
    */
  def registerProjection[Event, State](
      entityInfo: EntityInformation[?, Event, State],
      projection: Projection[Event],
      pollInterval: FiniteDuration = config.projectionPollInterval
  ): ProjectionRunner.ProjectionHandle = {
    val registeredProjections = registeredProjectionsRef.getAndUpdate(prev => prev + projection.name)
    if registeredProjections.contains(projection.name) then
      throw IllegalArgumentException(s"Projection with name ${projection.name} is already registered")
    ProjectionRunner.ProjectionHandle(ProjectionRunner(db, entityInfo, projection, pollInterval, autoPoll = !isInTest))
  }

  /** Only use in tests! */
  private[eventsourcing] def cleanRegisteredProjection(name: String): Unit =
    registeredProjectionsRef.getAndUpdate(prev => prev - name)
    ()

  private[eventsourcing] def closeDb(): Unit = db.close()

  private val registeredProjectionsRef = AtomicReference[Set[String]](Set.empty)

}

object EventSourcingService {

  case class Config(
      maxInMemoryEntities: Int = 2000,
      entityIdleShutdownTime: FiniteDuration = 1.hour,
      eventPageSize: Int = 50,
      removeIdleEntities: Boolean = true,
      projectionPollInterval: FiniteDuration = 200.millis
  )

  object Config:
    def default: Config = Config()

}
