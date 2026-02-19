package infra

import domain._
import scala.collection.mutable

// Event store per session
class InMemoryEventStore {
  private val m = mutable.Map.empty[SessionId, Vector[Event]].withDefaultValue(Vector.empty)
  def append(e: Event): Unit = m.update(e.sessionId, m(e.sessionId) :+ e)
  def load(sessionId: SessionId): Vector[Event] = m(sessionId)
}

// Slot registry (who is parked where)
class SlotRegistry {
  private val slots = mutable.Map.empty[SlotNo, Option[SessionId]]
  SlotNo.all.foreach(slot => slots.update(slot, None))

  def occupied(slot: SlotNo): Boolean = slots(slot).nonEmpty
  def sessionAt(slot: SlotNo): Option[SessionId] = slots(slot)
  def park(slot: SlotNo, sessionId: SessionId): Unit = slots.update(slot, Some(sessionId))
  def leave(slot: SlotNo): Unit = slots.update(slot, None)
  def snapshot(): Map[SlotNo, Option[SessionId]] = slots.toMap
}
