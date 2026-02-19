package infra

import domain._
import java.time.Instant
import java.util.UUID
import scala.collection.mutable

case class VersionConflict(sessionId: SessionId, expectedVersion: Long, actualVersion: Long)
sealed trait EnterAppendResult
case class EnteredAppended(sessionId: SessionId) extends EnterAppendResult
case class EnterRejectedOccupied(existingSessionId: SessionId) extends EnterAppendResult

// Event store per session
class InMemoryEventStore {
  private val m = mutable.Map.empty[SessionId, Vector[Event]].withDefaultValue(Vector.empty)
  private val lock = new Object

  def append(e: Event): Unit = lock.synchronized {
    m.update(e.sessionId, m(e.sessionId) :+ e)
  }

  def load(sessionId: SessionId): Vector[Event] = lock.synchronized {
    m(sessionId)
  }

  def loadWithVersion(sessionId: SessionId): (Vector[Event], Long) = {
    val events = load(sessionId)
    (events, events.length.toLong)
  }

  // Optimistic lock CAS:
  // append succeeds only when expectedVersion == currentVersion.
  def compareAndAppend(
    sessionId: SessionId,
    expectedVersion: Long,
    newEvents: Vector[Event]
  ): Either[VersionConflict, Long] = lock.synchronized {
    val current = m(sessionId)
    val actualVersion = current.length.toLong
    if (actualVersion != expectedVersion) {
      Left(VersionConflict(sessionId, expectedVersion, actualVersion))
    } else {
      m.update(sessionId, current ++ newEvents)
      Right(actualVersion + newEvents.length.toLong)
    }
  }

  // First-wins enter:
  // if the slot is already occupied, ignore this enter and return the existing session id.
  def appendEnterFirstWins(slot: SlotNo, at: Instant): Either[DomainError, EnterAppendResult] = lock.synchronized {
    activeSessionIdsAt(slot) match {
      case Vector() =>
        val sessionId = UUID.randomUUID().toString
        m.update(sessionId, m(sessionId) :+ CarEntered(sessionId, slot, at))
        Right(EnteredAppended(sessionId))
      case Vector((sessionId, _)) =>
        Right(EnterRejectedOccupied(sessionId))
      case many =>
        val sessionIds = many.map(_._1).mkString(",")
        Left(CorruptedEventStream("multiple-active-sessions", slot, s"multiple active sessions found: [$sessionIds]"))
    }
  }

  // Backward-compatible alias.
  def appendIfVersion(
    sessionId: SessionId,
    expectedVersion: Long,
    newEvents: Vector[Event]
  ): Either[VersionConflict, Long] =
    compareAndAppend(sessionId, expectedVersion, newEvents)

  def allEvents: Vector[Event] = lock.synchronized {
    m.valuesIterator.flatten.toVector
  }

  private def activeSessionIdsAt(slot: SlotNo): Vector[(SessionId, Instant)] = {
    val events = m.valuesIterator.flatten.toVector
    val enteredBySession = events.collect {
      case CarEntered(sessionId, s, at) if s == slot => sessionId -> at
    }.toMap
    val exitedSessions = events.collect {
      case CarExited(sessionId, s, _, _) if s == slot => sessionId
    }.toSet
    enteredBySession.filterNot { case (sessionId, _) => exitedSessions.contains(sessionId) }.toVector.sortBy(_._2)
  }
}

// Slot registry (who is parked where)
class SlotRegistry(store: InMemoryEventStore) {

  def occupied(slot: SlotNo): Either[DomainError, Boolean] =
    sessionAt(slot).map(_.nonEmpty)

  def sessionAt(slot: SlotNo): Either[DomainError, Option[SessionId]] = {
    val events = store.allEvents
    val enteredBySession = events.collect {
      case CarEntered(sessionId, s, at) if s == slot => sessionId -> at
    }.toMap
    val exitedSessions = events.collect {
      case CarExited(sessionId, s, _, _) if s == slot => sessionId
    }.toSet

    val activeSessions = enteredBySession.filterNot { case (sessionId, _) => exitedSessions.contains(sessionId) }
    val active = activeSessions.toVector.sortBy(_._2)
    active match {
      case Vector() =>
        Right(None)
      case Vector((sessionId, _)) =>
        Right(Some(sessionId))
      case many =>
        val sessionIds = many.map(_._1).mkString(",")
        Left(CorruptedEventStream("multiple-active-sessions", slot, s"multiple active sessions found: [$sessionIds]"))
    }
  }

  def snapshot(): Either[DomainError, Map[SlotNo, Option[SessionId]]] =
    SlotNo.all.foldLeft[Either[DomainError, Map[SlotNo, Option[SessionId]]]](Right(Map.empty)) { (acc, slot) =>
      for {
        m <- acc
        sid <- sessionAt(slot)
      } yield m.updated(slot, sid)
    }
}
