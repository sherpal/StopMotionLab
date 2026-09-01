package eventsourcing

import scalasql.simple.SimpleTable

/** Durable progress marker for one registered [[Projection]]: the offset (into
  * `raw_event_envelope`) up through which it has committed. Kept in the database, not in
  * memory, so a projection resumes from where it left off across restarts — this is what makes
  * "at least once" a guarantee rather than a best effort.
  */
private[eventsourcing] case class ProjectionCheckpoint(
    projectionName: String,
    lastOffset: Int,
    updatedAt: Long
)

private[eventsourcing] object ProjectionCheckpoint extends SimpleTable[ProjectionCheckpoint]
