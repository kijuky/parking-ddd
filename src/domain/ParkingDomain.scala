package domain

import java.time.{DayOfWeek, Duration, Instant, LocalTime, ZoneId, ZonedDateTime}

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
// - weekday day (09:00-18:00): 200 yen / 30 min (round up), no cap
// - weekday night (18:00-09:00): 200 yen / 60 min (round up), capped at 1800 yen
// - weekend day (09:00-18:00): 200 yen / 60 min (round up), capped at 1800 yen
// - weekend night (18:00-09:00): 100 yen / 60 min (round up), capped at 900 yen
object FeePolicy {
  val freeMinutes = 5L
  val pricingZone: ZoneId = ZoneId.of("Asia/Tokyo")
  val dayStart = LocalTime.of(9, 0)
  val dayEnd = LocalTime.of(18, 0)
  val weekdayDayRate = Rate(unitMinutes = 30L, unitYen = 200, maxYen = None)
  val weekdayNightRate = Rate(unitMinutes = 60L, unitYen = 200, maxYen = Some(1800))
  val weekendDayRate = Rate(unitMinutes = 60L, unitYen = 200, maxYen = Some(1800))
  val weekendNightRate = Rate(unitMinutes = 60L, unitYen = 100, maxYen = Some(900))

  def pricingSummary: String =
    s"最初の${freeMinutes}分無料 / 平日 昼(9:00-18:00) ${formatRate(weekdayDayRate)} / 平日 夜(18:00-翌9:00) ${formatRate(weekdayNightRate)} / 休日(土日) 昼(9:00-18:00) ${formatRate(weekendDayRate)} / 休日(土日) 夜(18:00-翌9:00) ${formatRate(weekendNightRate)}"

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
    var periodRate = rateAt(current, zoneId)
    var periodMinutes = 0L

    while (current.isBefore(end)) {
      val boundary = nextBoundary(current, zoneId)
      val segmentEnd = if (boundary.isBefore(end)) boundary else end
      val segmentMinutes = calcMinutes(current, segmentEnd)
      if (segmentMinutes > 0) periodMinutes += segmentMinutes
      current = segmentEnd

      val nextRateOpt = Option.when(current.isBefore(end))(rateAt(current, zoneId))
      if (nextRateOpt.forall(_ != periodRate)) {
        totalFee += feeForSegment(periodMinutes, periodRate)
        periodMinutes = 0L
        nextRateOpt.foreach(nextRate => periodRate = nextRate)
      }
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
    val zdt = ZonedDateTime.ofInstant(at, zoneId)
    val localTime = zdt.toLocalTime
    val isDay = !localTime.isBefore(dayStart) && localTime.isBefore(dayEnd)
    val weekend = isWeekend(zdt.getDayOfWeek)

    (weekend, isDay) match {
      case (false, true) => weekdayDayRate
      case (false, false) => weekdayNightRate
      case (true, true) => weekendDayRate
      case (true, false) => weekendNightRate
    }
  }

  private def nextBoundary(at: Instant, zoneId: ZoneId): Instant = {
    val zdt = ZonedDateTime.ofInstant(at, zoneId)
    val nextMidnight = zdt.toLocalDate.plusDays(1).atStartOfDay(zoneId)
    val nextDayStart = nextAtOrNextDay(zdt, dayStart, zoneId)
    val nextDayEnd = nextAtOrNextDay(zdt, dayEnd, zoneId)
    Vector(nextMidnight, nextDayStart, nextDayEnd).minBy(_.toInstant).toInstant
  }

  private def nextAtOrNextDay(current: ZonedDateTime, time: LocalTime, zoneId: ZoneId): ZonedDateTime = {
    val candidate = current.toLocalDate.atTime(time).atZone(zoneId)
    if (candidate.isAfter(current)) candidate else candidate.plusDays(1)
  }

  private def isWeekend(day: DayOfWeek): Boolean =
    day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
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
