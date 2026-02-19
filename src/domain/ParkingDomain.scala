package domain

import java.time.{Duration, Instant, LocalTime, ZoneId, ZonedDateTime}

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

final case class Rate(unitMinutes: Long, unitYen: Int, maxYen: Option[Int])

// Fee policy:
// - first 5 min free
// - day (09:00-18:00): 200 yen / 30 min (round up)
// - night (18:00-09:00): 200 yen / 60 min (round up), capped at 1800 yen
object FeePolicy {
  val freeMinutes = 5L
  val pricingZone: ZoneId = ZoneId.of("Asia/Tokyo")
  val dayStart = LocalTime.of(9, 0)
  val dayEnd = LocalTime.of(18, 0)
  val dayRate = Rate(unitMinutes = 30L, unitYen = 200, maxYen = None)
  val nightRate = Rate(unitMinutes = 60L, unitYen = 200, maxYen = Some(1800))

  def pricingSummary: String =
    s"最初の${freeMinutes}分無料 / 昼(9:00-18:00) ${formatRate(dayRate)} / 夜(18:00-翌9:00) ${formatRate(nightRate)}"

  def calcMinutes(start: Instant, end: Instant): Long =
    Math.max(0L, Duration.between(start, end).toMinutes)

  def calcFeeYen(start: Instant, end: Instant): Int =
    calcFeeYen(start, end, pricingZone)

  def calcFeeYen(start: Instant, end: Instant, zoneId: ZoneId): Int = {
    val totalMinutes = calcMinutes(start, end)
    if (totalMinutes <= freeMinutes) return 0
    if (!start.isBefore(end)) return 0

    var current = start
    var totalFee = 0
    while (current.isBefore(end)) {
      val rate = rateAt(current, zoneId)
      val boundary = nextBoundary(current, zoneId)
      val segmentEnd = if (boundary.isBefore(end)) boundary else end
      val minutes = calcMinutes(current, segmentEnd)
      if (minutes > 0) totalFee += feeForSegment(minutes, rate)
      current = segmentEnd
    }
    totalFee
  }

  private def feeForSegment(minutes: Long, rate: Rate): Int = {
    val units = ((minutes + rate.unitMinutes - 1) / rate.unitMinutes).toInt
    val rawFee = units * rate.unitYen
    rate.maxYen.map(max => Math.min(rawFee, max)).getOrElse(rawFee)
  }

  private def formatRate(rate: Rate): String = {
    val cap = rate.maxYen.map(max => s"(最大${max}円)").getOrElse("(上限なし)")
    s"${rate.unitMinutes}分ごと${rate.unitYen}円$cap"
  }

  private def rateAt(at: Instant, zoneId: ZoneId): Rate = {
    val localTime = ZonedDateTime.ofInstant(at, zoneId).toLocalTime
    if (!localTime.isBefore(dayStart) && localTime.isBefore(dayEnd)) dayRate else nightRate
  }

  private def nextBoundary(at: Instant, zoneId: ZoneId): Instant = {
    val zdt = ZonedDateTime.ofInstant(at, zoneId)
    val time = zdt.toLocalTime

    val next =
      if (!time.isBefore(dayStart) && time.isBefore(dayEnd))
        zdt.withHour(dayEnd.getHour).withMinute(0).withSecond(0).withNano(0)
      else if (time.isBefore(dayStart))
        zdt.withHour(dayStart.getHour).withMinute(0).withSecond(0).withNano(0)
      else
        zdt.toLocalDate.plusDays(1).atTime(dayStart).atZone(zoneId)
    next.toInstant
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
    val feeYen = FeePolicy.calcFeeYen(correctedEnteredAt, correctedExitedAt)
    SessionReconciled(sessionId, slot, correctedEnteredAt, correctedExitedAt, minutes, feeYen, note, at)
  }
}

// Aggregate (per session)
case class ParkingSession(sessionId: SessionId, slot: SlotNo, enteredAt: Instant) {
  def exit(at: Instant): (CarExited, FeeCalculated) = {
    val minutes = FeePolicy.calcMinutes(enteredAt, at)
    val fee = FeePolicy.calcFeeYen(enteredAt, at)
    (CarExited(sessionId, slot, at), FeeCalculated(sessionId, slot, minutes, fee))
  }
}
