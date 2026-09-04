package eventsourcing

import be.doeraene.utils.testshenanigans.{HasTestPower, OnlyInTest}

import java.nio.file.Paths

private given OnlyInTest = new HasTestPower {}.onlyInTest

def cleanEventSourcingService(service: EventSourcingService, dbPath: Option[String] = Some("./test-data/event-sourcing")): Unit = {
  service.closeDb()
  dbPath.foreach { path =>
    os.remove.all(os.Path(Paths.get(path).toAbsolutePath))
  }
}

def clearSupervisorMemory(service: EventSourcingService): Unit =
  service.clearMemory()
