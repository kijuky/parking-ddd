package domain

import java.time.{Duration, Instant}

final case class SlotNo private (value: Int) {
  override def toString: String = value.toString
}
object SlotNo {
  val Min = 1
  val Max = 9

  def from(value: Int): Option[SlotNo] =
    Option.when(value >= Min && value <= Max)(SlotNo(value))

  val all: Vector[SlotNo] = (Min to Max).flatMap(from).toVector
}
type SessionId = String

// Commands
sealed trait Command
case class Pressed(slot: SlotNo, at: Instant) extends Command
case class RequestRepair(sessionId: SessionId, slot: SlotNo, reason: String, at: Instant) extends Command
case class ReconcileSession(
  sessionId: SessionId,
  slot: SlotNo,
  correctedEnteredAt: Instant,
  correctedExitedAt: Instant,
  note: String,
  at: Instant
) extends Command

// Events
sealed trait Event { def sessionId: SessionId }
case class CarEntered(sessionId: SessionId, slot: SlotNo, at: Instant) extends Event
case class CarExited(sessionId: SessionId, slot: SlotNo, at: Instant) extends Event
case class FeeCalculated(sessionId: SessionId, slot: SlotNo, minutes: Long, feeYen: Int) extends Event
case class DataRepairRequested(sessionId: SessionId, slot: SlotNo, reason: String, at: Instant) extends Event
case class SessionReconciled(
  sessionId: SessionId,
  slot: SlotNo,
  correctedEnteredAt: Instant,
  correctedExitedAt: Instant,
  correctedMinutes: Long,
  correctedFeeYen: Int,
  note: String,
  at: Instant
) extends Event

case class CorruptedEventStream(sessionId: SessionId, slot: SlotNo, details: String)
  extends RuntimeException(s"Corrupted event stream: sessionId=$sessionId slot=$slot details=$details")

// Fee policy: first 5 min free, then 200 yen / 30 min (round up)
object FeePolicy {
  val freeMinutes = 5L
  val unitMinutes = 30L
  val unitYen = 200
  def calcMinutes(start: Instant, end: Instant): Long =
    Math.max(0L, Duration.between(start, end).toMinutes)
  def calcFeeYen(minutes: Long): Int = {
    if (minutes <= freeMinutes) 0
    else {
      val units = ((minutes + unitMinutes - 1) / unitMinutes).toInt // ceil
      units * unitYen
    }
  }
}

object RepairPolicy {
  def reconcile(
    sessionId: SessionId,
    slot: SlotNo,
    correctedEnteredAt: Instant,
    correctedExitedAt: Instant,
    note: String,
    at: Instant
  ): SessionReconciled = {
    val minutes = FeePolicy.calcMinutes(correctedEnteredAt, correctedExitedAt)
    val feeYen = FeePolicy.calcFeeYen(minutes)
    SessionReconciled(sessionId, slot, correctedEnteredAt, correctedExitedAt, minutes, feeYen, note, at)
  }
}

// Aggregate (per session)
case class ParkingSession(sessionId: SessionId, slot: SlotNo, enteredAt: Instant) {
def exit(at: Instant): (CarExited, FeeCalculated) = {
    val minutes = FeePolicy.calcMinutes(enteredAt, at)
    val fee = FeePolicy.calcFeeYen(minutes)
    (CarExited(sessionId, slot, at), FeeCalculated(sessionId, slot, minutes, fee))
  }
}
