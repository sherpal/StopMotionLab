package eventsourcing

import be.doeraene.services.database.DatabaseService
import be.doeraene.services.database.tables.RawEventEnvelope as DBRawEventEnvelope
import be.doeraene.utils.testshenanigans.HasTestPower
import castor.Context
import eventsourcing.EventSourcingService.Config
import io.circe.Codec
import scalasql.simple.{DbClient, SqliteDialect}

import java.nio.file.Paths
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt

class EventSourcingServiceTest extends munit.FunSuite with HasTestPower {
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
        (command, entity, _) => commandHandler(command, entity)
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
        (command, entity, _) => commandHandler(command, entity)
      )
  }

  def eventSourcingWithConfig(config: Config) =
    FunFixture[(EventSourcingService, scalasql.DbClient.DataSource, castor.Context.Test)](
      setup = { test =>
        val databaseService           = DatabaseService(Paths.get("./test-data/event-sourcing"), inTest = true)
        given ac: castor.Context.Test = castor.Context.Test()
        (
          EventSourcingService(config, SqlEventStore(databaseService.client), CastorScheduler(), isInTest = true),
          databaseService.client,
          ac
        )
      },
      teardown = { (service, _, ac) =>
        ac.waitForInactivity()
        cleanEventSourcingService(service)
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

    var upToDateState = info.initialState

    val entitySubscription = eventSourcing.subscribe(
      1,
      info.entityKind,
      new castor.SimpleActor[EntityUpdateNotification[Entity]]() {
        var maxSequenceNumberSeen = -1

        override def run(msg: EntityUpdateNotification[BasicEntityDefs.Entity]): Unit =
          if msg.sequenceNumber > maxSequenceNumberSeen then {
            maxSequenceNumberSeen = msg.sequenceNumber
            upToDateState = msg.state
          }
      }
    )

    entity.send(Increment())
    entity.send(Get())
    ac.waitForInactivity()
    assertEquals(response, 1)
    entity.send(Increment())
    entity.send(Get())
    ac.waitForInactivity()
    assertEquals(response, 2)
    assertEquals(upToDateState.count, response)

    // stop subscribing
    entitySubscription.unsubscribe()

    for (j <- 1 to 100) do {
      Future(entity.send(Increment()))
    }

    ac.waitForInactivity()
    entity.send(Get())
    ac.waitForInactivity()
    assertEquals(response, 102)
    assertEquals(upToDateState.count, 2)

    assertEquals(Await.result(eventSourcing.lastEntityId(info.entityKind), 1.second), Option(1))

    val db = client.getAutoCommitClientConnection
    try {
      val events: Vector[EventEnvelope[Event, Entity]] =
        db.run(DBRawEventEnvelope.select).toVector.map(SqlEventStore.toCommon).map(info.decodeEnvelope)
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
          db.run(DBRawEventEnvelope.select).toVector.map(SqlEventStore.toCommon).map(info.decodeEnvelope)
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
      val events = db.run(DBRawEventEnvelope.select).toVector.map(SqlEventStore.toCommon).map(info.decodeEnvelope)
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
        val rows = db.run(DBRawEventEnvelope.select).toVector
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
    try assertEquals(db.run(DBRawEventEnvelope.select).toVector, Vector.empty)
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
        DBRawEventEnvelope.insert
          .values(
            DBRawEventEnvelope(
              offset = 0,
              entityId = 1,
              sequenceNumber = 1,
              eventPayload = "not valid json",
              entityKind = info.entityKind.name,
              timestamp = 0L
            )
          )
          .skipColumns(_.offset)
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

    val info = entityInfo((command, state) =>
      command match {
        case Increment() => Effect.Persist(Event())
        case Get()       => Effect.Ignore[Event, Entity]()
      }
    )

    def makeEntity(id: Int) = eventSourcing.entity[Command, Event, Entity](id, info)

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

    val lastId = Await.result(eventSourcing.lastEntityId(info.entityKind), 1.second)
    assertEquals(lastId, Option(idCount))

    val db = client.getAutoCommitClientConnection
    try {
      val counts = (1 to idCount).map(id => id -> db.run(DBRawEventEnvelope.select.filter(_.entityId === id)).size)
      val bad    = counts.filter(_._2 != incrementsPerId)
      assert(bad.isEmpty, s"expected every id to have exactly $incrementsPerId persisted events, but got: $bad")
    } finally db.close()
  }

  eventSourcing.test("a projection catches up on pre-existing events and then keeps up with new ones") {
    (eventSourcing, _, ac) =>
      given castor.Context = ac

      import BasicEntityDefs.*

      val info = entityInfo((command, state) =>
        command match {
          case Increment() => Effect.Persist(Event())
          case Get()       => Effect.Ignore()
        }
      )
      val entity = eventSourcing.entity[Command, Event, Entity](1, info)

      // These are persisted *before* the projection is even registered.
      for (_ <- 1 to 5) entity.send(Increment())
      ac.waitForInactivity()

      val seen       = scala.collection.mutable.ArrayBuffer.empty[Int]
      val projection = Projection[Event]("counter-log", Projection.Semantics.AtLeastOnce) { (_, envelope) =>
        seen.append(envelope.sequenceNumber)
      }
      val handle = eventSourcing.registerProjection(info, projection)
      ac.waitForInactivity()

      assertEquals(seen.toVector, (1 to 5).toVector) // caught up on everything that already existed

      entity.send(Increment())
      entity.send(Increment())
      ac.waitForInactivity()
      // Auto-polling is off in test mode (see ProjectionRunner's `autoPoll`), so — same as with
      // the real poll timer in production — a new tick is what notices these new events.
      handle.poke()
      ac.waitForInactivity()

      assertEquals(seen.toVector, (1 to 7).toVector) // and kept up with what came after
  }

  eventSourcing.test("a projection only sees events of its own entity kind") { (eventSourcing, _, ac) =>
    given castor.Context = ac

    import BasicEntityDefs.{Command, Entity, Event, Get, Increment}
    import OtherEntityDefs.{Add, GetTotal, OtherCommand, OtherEntity, OtherEvent}

    val basicInfo = BasicEntityDefs.entityInfo((command, state) =>
      command match {
        case Increment() => Effect.Persist(Event())
        case Get()       => Effect.Ignore()
      }
    )
    val otherInfo = OtherEntityDefs.entityInfo((command, state) =>
      command match {
        case Add(n)     => Effect.Persist(OtherEvent(n))
        case GetTotal() => Effect.Ignore()
      }
    )

    val counterEntity = eventSourcing.entity[Command, Event, Entity](1, basicInfo)
    val otherEntity   = eventSourcing.entity[OtherCommand, OtherEvent, OtherEntity](1, otherInfo)

    counterEntity.send(Increment())
    otherEntity.send(Add(10))
    otherEntity.send(Add(5))
    ac.waitForInactivity()

    val seen       = scala.collection.mutable.ArrayBuffer.empty[Event]
    val projection = Projection[Event]("counter-only", Projection.Semantics.AtLeastOnce) { (_, envelope) =>
      seen.append(envelope.event)
    }
    eventSourcing.registerProjection(basicInfo, projection)
    ac.waitForInactivity()

    assertEquals(seen.toVector, Vector(Event())) // never sees OtherEvent, even though both are entity id 1
  }

  eventSourcing.test("at-least-once projections retry a failed event and can run it more than once") {
    (eventSourcing, _, ac) =>
      given castor.Context = ac

      import BasicEntityDefs.*

      val info = entityInfo((command, state) =>
        command match {
          case Increment() => Effect.Persist(Event())
          case Get()       => Effect.Ignore()
        }
      )
      val entity = eventSourcing.entity[Command, Event, Entity](1, info)
      for (_ <- 1 to 3) entity.send(Increment())
      ac.waitForInactivity()

      val invocations  = scala.collection.mutable.ArrayBuffer.empty[Int]
      var failuresLeft = 1 // event #2 fails on its first attempt, then succeeds on retry
      val projection   = Projection[Event]("flaky", Projection.Semantics.AtLeastOnce) { (_, envelope) =>
        invocations.append(envelope.sequenceNumber)
        if envelope.sequenceNumber == 2 && failuresLeft > 0 then {
          failuresLeft -= 1
          throw RuntimeException("simulated transient failure")
        }
      }
      val handle = eventSourcing.registerProjection(info, projection)
      ac.waitForInactivity()

      // event 1 succeeded and was checkpointed; event 2 failed, so the batch stopped there —
      // event 3 was never even attempted this round
      assertEquals(invocations.toVector, Vector(1, 2))

      val isUpToDate = Await.result(handle.isUpToDate, 1.second)
      assert(!isUpToDate, "projection should not be up to date after a failed event")

      handle.poke() // retry from the checkpoint: event 2 runs again (and succeeds), then event 3
      ac.waitForInactivity()

      // event 2 ran twice — exactly the "guaranteed at least once, maybe more" contract
      assertEquals(invocations.toVector, Vector(1, 2, 2, 3))
  }

  eventSourcing.test("at-most-once projections never retry — a failed event is skipped for good") {
    (eventSourcing, _, ac) =>
      given castor.Context = ac

      import BasicEntityDefs.*

      val info = entityInfo((command, state) =>
        command match {
          case Increment() => Effect.Persist(Event())
          case Get()       => Effect.Ignore()
        }
      )
      val entity = eventSourcing.entity[Command, Event, Entity](1, info)
      for (_ <- 1 to 3) entity.send(Increment())
      ac.waitForInactivity()

      val invocations = scala.collection.mutable.ArrayBuffer.empty[Int]
      val projection  = Projection[Event]("best-effort", Projection.Semantics.AtMostOnce) { (_, envelope) =>
        invocations.append(envelope.sequenceNumber)
        if envelope.sequenceNumber == 2 then throw new RuntimeException("simulated permanent failure")
      }
      val handle = eventSourcing.registerProjection(info, projection)
      ac.waitForInactivity()

      assertEquals(invocations.toVector, Vector(1, 2, 3)) // ran once each, in order, despite #2 failing

      handle.poke() // nothing left to do — #2 is never retried, the checkpoint already moved past it
      ac.waitForInactivity()
      assertEquals(invocations.toVector, Vector(1, 2, 3))
  }

  eventSourcing.test("a projection resumes from its persisted checkpoint instead of restarting from scratch") {
    (eventSourcing, _, ac) =>
      given castor.Context = ac

      import BasicEntityDefs.*

      val info = entityInfo((command, state) =>
        command match {
          case Increment() => Effect.Persist(Event())
          case Get()       => Effect.Ignore()
        }
      )
      val entity = eventSourcing.entity[Command, Event, Entity](1, info)
      for (_ <- 1 to 3) entity.send(Increment())
      ac.waitForInactivity()

      val firstRunInvocations = scala.collection.mutable.ArrayBuffer.empty[Int]
      eventSourcing.registerProjection(
        info,
        Projection[Event]("resumable", Projection.Semantics.AtLeastOnce) { (_, envelope) =>
          firstRunInvocations.append(envelope.sequenceNumber)
        }
      )
      ac.waitForInactivity()
      assertEquals(firstRunInvocations.toVector, Vector(1, 2, 3))

      for (_ <- 1 to 2) entity.send(Increment())
      ac.waitForInactivity()

      // A brand new runner under the *same* projection name, simulating a process restart: it
      // must resume from the persisted checkpoint (offset of event 3), not replay events 1-3.
      val secondRunInvocations = scala.collection.mutable.ArrayBuffer.empty[Int]
      eventSourcing.cleanRegisteredProjection("resumable") // allow re-registering the same name in this test
      val projectionHandle = eventSourcing.registerProjection(
        info,
        Projection[Event]("resumable", Projection.Semantics.AtLeastOnce) { (_, envelope) =>
          secondRunInvocations.append(envelope.sequenceNumber)
        }
      )
      ac.waitForInactivity()

      assertEquals(secondRunInvocations.toVector, Vector(4, 5))

      val isFinished = Await.result(projectionHandle.isUpToDate, 1.second)
      assert(isFinished, "projection should be up to date after processing all events")
  }

}
