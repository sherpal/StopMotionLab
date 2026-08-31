package eventsourcing

import scalasql.simple.SimpleTable

private[eventsourcing] case class RawEventEnvelope(
    entityId: Int,
    sequenceNumber: Int,
    eventPayload: String,
    entityKind: String,
    timestamp: Long
)

private[eventsourcing] object RawEventEnvelope extends SimpleTable[RawEventEnvelope]
