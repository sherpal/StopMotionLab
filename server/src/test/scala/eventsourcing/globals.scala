package eventsourcing

import java.nio.file.Paths

def cleanEventSourcingService(service: EventSourcingService, dbPath: String = "./test-data/event-sourcing"): Unit = {
  service.closeDb()
  os.remove.all(os.Path(Paths.get(dbPath).toAbsolutePath))
}

def clearSupervisorMemory(service: EventSourcingService): Unit =
  service.clearMemory()
