package eventsourcing

import java.time.temporal.{ChronoUnit, TemporalUnit}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

class EventSourcingService(
    config: EventSourcingService.Config,
    client: scalasql.DbClient.DataSource,
    isInTest: Boolean = false
)(using
    castor.Context
) {

  private val db = client.getAutoCommitClientConnection

  private val supervisor =
    Supervisor(db, config = config.copy(removeIdleEntities = !isInTest && config.removeIdleEntities))

  def entity[Command, Event, State](id: Int, info: EntityInformation[Command, Event, State]): castor.Actor[Command] =
    castor.ProxyActor[Command, Supervisor.SupervisorMessage](Supervisor.EntityCommand(id, info, _), supervisor)

  private[eventsourcing] def closeDb(): Unit = db.close()

}

object EventSourcingService {

  case class Config(
      maxInMemoryEntities: Int = 2000,
      entityIdleShutdownTime: FiniteDuration = 1.hour,
      eventPageSize: Int = 50,
      removeIdleEntities: Boolean = true
  )

  object Config:
    def default: Config = Config()

}
