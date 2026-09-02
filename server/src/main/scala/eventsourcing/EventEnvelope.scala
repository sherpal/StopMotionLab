package eventsourcing

case class EventEnvelope[Event, Entity](
    entityId: Int,
    sequenceNumber: Int,
    event: Event,
    timestamp: Long // in seconds
)
