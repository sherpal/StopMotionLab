package eventsourcing

import be.doeraene.services.database.DatabaseService
import castor.Context
import eventsourcing.EventSourcingService.Config
import io.circe.Codec
import scalasql.simple.{DbClient, SqliteDialect}

import java.nio.file.Paths
import scala.concurrent.Future

class EventSourcingServiceTest extends munit.FunSuite {
  import SqliteDialect.*

  object BasicEntityDefs {
    case class Event() derives Codec
    sealed trait Command
    case class Increment() extends Command
    case class Get()       extends Command
    case class Entity(count: Int)

    def entityInfo(
        commandHandler: (Command, Entity) => Effect[Event, Entity]
    ): EntityInformation[Command, Event, Entity] =
      EntityInformation.usingCirceSerialization[Command, Event, Entity](
        Entity(0),
        (_, state) => Entity(state.count + 1),
        commandHandler
      )
  }

  // A second, unrelated entity kind, used to check that different kinds sharing the same
  // numeric id don't interfere with one another.
  object OtherEntityDefs {
    case class OtherEvent(delta: Int) derives Codec
    sealed trait OtherCommand
    case class Add(n: Int) extends OtherCommand
    case class GetTotal()  extends OtherCommand
    case class OtherEntity(total: Int)

    def entityInfo(
        commandHandler: (OtherCommand, OtherEntity) => Effect[OtherEvent, OtherEntity]
    ): EntityInformation[OtherCommand, OtherEvent, OtherEntity] =
      EntityInformation.usingCirceSerialization[OtherCommand, OtherEvent, OtherEntity](
        OtherEntity(0),
        (event, state) => OtherEntity(state.total + event.delta),
        commandHandler
      )
  }

  def eventSourcingWithConfig(config: Config) =
    FunFixture[(EventSourcingService, scalasql.DbClient.DataSource, castor.Context.Test)](
      setup = { test =>
        val databaseService           = DatabaseService(Paths.get("./test-data/event-sourcing"), inTest = true)
        given ac: castor.Context.Test = castor.Context.Test()
        (
          EventSourcingService(config, databaseService.client, isInTest = true),
          databaseService.client,
          ac
        )
      },
      teardown = { (service, _, ac) =>
        ac.waitForInactivity()
        service.closeDb()
        os.remove.all(os.Path(Paths.get("./test-data/event-sourcing").toAbsolutePath))
      }
    )

  val eventSourcing: FunFixture[(EventSourcingService, DbClient.DataSource, Context.Test)] = eventSourcingWithConfig(
    Config.default
  )

  eventSourcing.test("I can create an entity, increment its state") { (eventSourcing, client, ac) =>
    import BasicEntityDefs.*

    given castor.Context = ac

    var response: Int = -1

    val info = entityInfo((command, state) =>
      command match {
        case Increment() => Effect.Persist(Event())
        case Get()       => Effect.Ignore[Event, Entity]().thenRun(_ => response = state.count)
      }
    )
    val entity = eventSourcing.entity[Command, Event, Entity](1, info)

    entity.send(Increment())
    entity.send(Get())
    ac.waitForInactivity()
    assertEquals(response, 1)
    entity.send(Increment())
    entity.send(Get())
    ac.waitForInactivity()
    assertEquals(response, 2)

    for (j <- 1 to 100) do {
      Future(entity.send(Increment()))
    }

    ac.waitForInactivity()
    entity.send(Get())
    ac.waitForInactivity()
    assertEquals(response, 102)

    val db = client.getAutoCommitClientConnection
    try {
      val events: Vector[EventEnvelope[Event, Entity]] =
        db.run(RawEventEnvelope.select).toVector.map(info.decodeEnvelope)
      assertEquals(events.map(_.event), Vector.fill(102)(Event()))
    } finally {
      db.close()
    }

  }

  eventSourcing.test("I can ask twice for the same entity and it does not change anything") { (eventSourcing, _, ac) =>
    import BasicEntityDefs.*

    given castor.Context = ac

    var response: Int = -1

    def makeEntity = eventSourcing.entity[Command, Event, Entity](
      1,
      entityInfo((command, state) =>
        command match {
          case Increment() => Effect.Persist(Event())
          case Get()       => Effect.Ignore[Event, Entity]().thenRun(_ => response = state.count)
        }
      )
    )

    val entity1 = makeEntity
    val entity2 = makeEntity

    for (j <- 1 to 100) do {
      val entity = if j % 2 == 0 then entity1 else entity2
      Future(entity.send(Increment()))
    }
    ac.waitForInactivity()

    entity1.send(Get())
    ac.waitForInactivity()
    assertEquals(response, 100)
    response = -1
    entity2.send(Get())
    ac.waitForInactivity()
    assertEquals(response, 100)
  }

  eventSourcingWithConfig(Config.default.copy(maxInMemoryEntities = 1))
    .test("We can put to sleep and awaken entities") { (eventSourcing, client, ac) =>
      given castor.Context = ac

      import BasicEntityDefs.*

      var response: Int = -1

      val info = entityInfo((command, state) =>
        command match {
          case Increment() => Effect.Persist(Event())
          case Get()       => Effect.Ignore[Event, Entity]().thenRun(_ => response = state.count)
        }
      )

      def makeEntity(id: Int) = eventSourcing.entity[Command, Event, Entity](id, info)

      val firstEntity1 = makeEntity(1)

      firstEntity1.send(Increment())
      firstEntity1.send(Get())
      ac.waitForInactivity()
      assertEquals(response, 1)

      val entity2 = makeEntity(2)
      entity2.send(Increment())

      ac.waitForInactivity()

      val secondEntity1 = makeEntity(1)
      secondEntity1.send(Increment())
      secondEntity1.send(Increment())
      secondEntity1.send(Get())
      secondEntity1.send(Increment())
      ac.waitForInactivity()
      assertEquals(response, 3)

      entity2.send(Get())
      entity2.send(Increment())
      ac.waitForInactivity()
      assertEquals(response, 1)

      val db = client.getAutoCommitClientConnection
      try {
        val events: Vector[EventEnvelope[Event, Entity]] =
          db.run(RawEventEnvelope.select).toVector.map(info.decodeEnvelope)
        assertEquals(events.count(_.entityId == 1), 4)
        assertEquals(events.count(_.entityId == 2), 2)
      } finally {
        db.close()
      }
    }

  eventSourcingWithConfig(Config.default.copy(eventPageSize = 2, maxInMemoryEntities = 1)).test(
    "recovering an entity replays events across multiple pages, applying commands sent during recovery in order"
  ) { (eventSourcing, client, ac) =>
    given castor.Context = ac

    import BasicEntityDefs.*

    var response: Int = -1

    val info = entityInfo((command, state) =>
      command match {
        case Increment() => Effect.Persist(Event())
        case Get()       => Effect.Ignore[Event, Entity]().thenRun(_ => response = state.count)
      }
    )
    def makeEntity(id: Int) = eventSourcing.entity[Command, Event, Entity](id, info)

    // Persist 5 events for entity 1: with eventPageSize = 2, recovering this entity later
    // requires 3 separate LoadNext pages (2 + 2 + 1).
    val entity1 = makeEntity(1)
    for (_ <- 1 to 5) entity1.send(Increment())
    ac.waitForInactivity()

    // Evict entity 1 from memory (maxInMemoryEntities = 1) by touching a different entity.
    val entity2 = makeEntity(2)
    entity2.send(Increment())
    ac.waitForInactivity()

    // Recreate entity 1: this forces a fresh multi-page recovery. Queue several commands
    // immediately, without waiting, so at least some of them arrive while the entity is
    // still replaying earlier pages (exercising LoadingState's command-buffering).
    val reloadedEntity1 = makeEntity(1)
    reloadedEntity1.send(Increment())
    reloadedEntity1.send(Get())
    reloadedEntity1.send(Increment())
    ac.waitForInactivity()
    assertEquals(response, 6) // 5 replayed events + the first queued increment

    reloadedEntity1.send(Get())
    ac.waitForInactivity()
    assertEquals(response, 7) // + the second queued increment

    val db = client.getAutoCommitClientConnection
    try {
      val events = db.run(RawEventEnvelope.select).toVector.map(info.decodeEnvelope)
      assertEquals(events.count(_.entityId == 1), 7)
    } finally db.close()
  }

  eventSourcing.test("entities of different kinds sharing the same id are fully isolated") {
    (eventSourcing, client, ac) =>
      given castor.Context = ac

      import BasicEntityDefs.{Command, Entity, Event, Get, Increment}
      import OtherEntityDefs.{Add, GetTotal, OtherCommand, OtherEntity, OtherEvent}

      var counterResponse: Int = -1

      val basicEntityInfo = BasicEntityDefs.entityInfo((command, state) =>
        command match {
          case Increment() => Effect.Persist(Event())
          case Get()       => Effect.Ignore[Event, Entity]().thenRun(_ => counterResponse = state.count)
        }
      )

      val counterEntity = eventSourcing.entity[Command, Event, Entity](1, basicEntityInfo)

      var totalResponse: Int = -1

      val otherEntityInfo = OtherEntityDefs.entityInfo((command, state) =>
        command match {
          case Add(n)     => Effect.Persist(OtherEvent(n))
          case GetTotal() => Effect.Ignore[OtherEvent, OtherEntity]().thenRun(_ => totalResponse = state.total)
        }
      )
      val otherEntity = eventSourcing.entity[OtherCommand, OtherEvent, OtherEntity](1, otherEntityInfo)

      counterEntity.send(Increment())
      otherEntity.send(Add(10))
      otherEntity.send(Add(5))
      ac.waitForInactivity()

      counterEntity.send(Get())
      otherEntity.send(GetTotal())
      ac.waitForInactivity()

      assertEquals(counterResponse, 1)
      assertEquals(totalResponse, 15)

      val db = client.getAutoCommitClientConnection
      try {
        val rows = db.run(RawEventEnvelope.select).toVector
        assertEquals(rows.count(_.entityKind == basicEntityInfo.entityKind.name), 1)
        assertEquals(rows.count(_.entityKind == otherEntityInfo.entityKind.name), 2)
      } finally db.close()
  }

  eventSourcing.test("the Ignore effect never persists an event") { (eventSourcing, client, ac) =>
    import BasicEntityDefs.*

    given castor.Context = ac

    var response: Int = -1

    val entity = eventSourcing.entity[Command, Event, Entity](
      1,
      entityInfo((command, state) =>
        command match {
          case Increment() => Effect.Persist(Event())
          case Get()       => Effect.Ignore[Event, Entity]().thenRun(_ => response = state.count)
        }
      )
    )

    entity.send(Get())
    entity.send(Get())
    ac.waitForInactivity()
    assertEquals(response, 0)

    val db = client.getAutoCommitClientConnection
    try assertEquals(db.run(RawEventEnvelope.select).toVector, Vector.empty)
    finally db.close()
  }

  eventSourcing.test("thenReply sends the post-effect state to another actor") { (eventSourcing, _, ac) =>
    import BasicEntityDefs.*

    given castor.Context = ac

    val received  = scala.collection.mutable.ArrayBuffer.empty[Int]
    val collector = new castor.SimpleActor[Int] {
      def run(msg: Int): Unit = received.append(msg)
    }

    val entity = eventSourcing.entity[Command, Event, Entity](
      1,
      entityInfo((command, state) =>
        command match {
          case Increment() => Effect.Persist(Event()).thenReply(collector)(_.count)
          case Get()       => Effect.Ignore[Event, Entity]()
        }
      )
    )

    entity.send(Increment())
    entity.send(Increment())
    ac.waitForInactivity()

    assertEquals(received.toVector, Vector(1, 2))
  }

  // This documents a currently-fragile behavior rather than a desired one: a single event
  // that fails to decode leaves the entity permanently stuck in its loading state instead of
  // surfacing an error, because castor's default failure handling only logs the exception
  // (see castor.BaseActor.runBatch0 -> Context#reportFailure) instead of propagating it.
  // If RawEventEnvelope.EventSerializer's `decode`/`decodeEnvelope` is hardened to fail more
  // gracefully, this test's expectations should be revisited.
  eventSourcing.test(
    "a corrupted event payload silently wedges the entity instead of surfacing an error (known issue)"
  ) { (eventSourcing, client, ac) =>
    import BasicEntityDefs.*

    given castor.Context = ac

    val info = entityInfo((_, _) => Effect.Ignore())

    val db = client.getAutoCommitClientConnection
    try {
      db.run(
        RawEventEnvelope.insert.values(
          RawEventEnvelope(
            entityId = 1,
            sequenceNumber = 1,
            eventPayload = "not valid json",
            entityKind = info.entityKind.name,
            timestamp = 0L
          )
        )
      )
    } finally db.close()

    var response: Int = -1
    val entity        = eventSourcing.entity[Command, Event, Entity](
      1,
      entityInfo((command, state) =>
        command match {
          case Increment() => Effect.Persist(Event())
          case Get()       => Effect.Ignore[Event, Entity]().thenRun(_ => response = state.count)
        }
      )
    )

    entity.send(Get())
    ac.waitForInactivity()

    assertEquals(response, -1)
  }

  eventSourcingWithConfig(Config.default.copy(maxInMemoryEntities = 1)).test(
    "rapid churn across many entities does not lose or duplicate events for any single entity"
  ) { (eventSourcing, client, ac) =>
    import BasicEntityDefs.*

    given castor.Context = ac

    def makeEntity(id: Int) = eventSourcing.entity[Command, Event, Entity](
      id,
      entityInfo((command, state) =>
        command match {
          case Increment() => Effect.Persist(Event())
          case Get()       => Effect.Ignore[Event, Entity]()
        }
      )
    )

    // With only one entity kept in memory at a time, sending to `idCount` distinct ids in a
    // tight, concurrent loop constantly evicts and recreates entities. This is a regression
    // test for a possible race where an entity gets evicted while it still has commands
    // in flight, and a freshly-recreated actor for the same id ends up racing it for the
    // same sequence numbers.
    val idCount         = 15
    val incrementsPerId = 20
    for (_ <- 1 to incrementsPerId; id <- 1 to idCount) {
      Future(makeEntity(id).send(Increment()))
    }
    ac.waitForInactivity()

    val db = client.getAutoCommitClientConnection
    try {
      val counts = (1 to idCount).map(id => id -> db.run(RawEventEnvelope.select.filter(_.entityId === id)).size)
      val bad    = counts.filter(_._2 != incrementsPerId)
      assert(bad.isEmpty, s"expected every id to have exactly $incrementsPerId persisted events, but got: $bad")
    } finally db.close()
  }

}
