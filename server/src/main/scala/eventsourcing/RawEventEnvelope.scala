package eventsourcing

import scalasql.simple.SimpleTable

private[eventsourcing] case class RawEventEnvelope(
    offset: Int,
    entityId: Int,
    sequenceNumber: Int,
    eventPayload: String,
    entityKind: String,
    timestamp: Long
)

private[eventsourcing] object RawEventEnvelope extends SimpleTable[RawEventEnvelope]
