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

sealed trait CapRule
case object Uncapped extends CapRule
final case class Capped(maxYen: Int) extends CapRule

final case class Rate(unitMinutes: Long, unitYen: Int, capRule: CapRule) {
  def applyCap(feeYen: Int): Int = capRule match {
    case Uncapped => feeYen
    case Capped(maxYen) => Math.min(feeYen, maxYen)
  }
}

final case class TimeBand(name: String, start: LocalTime, end: LocalTime, rate: Rate) {
  // end is exclusive. If start > end, band spans midnight.
  def contains(time: LocalTime): Boolean =
    if (!start.isAfter(end)) !time.isBefore(start) && time.isBefore(end)
    else !time.isBefore(start) || time.isBefore(end)

  def nextBoundary(after: ZonedDateTime): Instant = {
    val startBoundary = nextAtOrNextDay(after, start)
    val endBoundary = nextAtOrNextDay(after, end)
    if (startBoundary.isBefore(endBoundary)) startBoundary.toInstant else endBoundary.toInstant
  }

  private def nextAtOrNextDay(current: ZonedDateTime, time: LocalTime): ZonedDateTime = {
    val candidate = current.toLocalDate.atTime(time).atZone(current.getZone)
    if (candidate.isAfter(current)) candidate else candidate.plusDays(1)
  }
}

final case class PricingCalendar(
  zoneId: ZoneId,
  freeMinutes: Long,
  weekdayBands: Vector[TimeBand],
  weekendBands: Vector[TimeBand]
) {
  def bandsAt(day: DayOfWeek): Vector[TimeBand] =
    if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) weekendBands else weekdayBands
}

// Fee policy:
// - first 5 min free
// - weekday day (09:00-18:00): 200 yen / 30 min (round up), no cap
// - weekday night (18:00-09:00): 200 yen / 60 min (round up), capped at 1800 yen
// - weekend day (09:00-18:00): 200 yen / 60 min (round up), capped at 1800 yen
// - weekend night (18:00-09:00): 100 yen / 60 min (round up), capped at 900 yen
object FeePolicy {
  val dayStart = LocalTime.of(9, 0)
  val dayEnd = LocalTime.of(18, 0)
  val dayBand = "day"
  val nightBand = "night"

  val weekdayDayRate = Rate(unitMinutes = 30L, unitYen = 200, capRule = Uncapped)
  val weekdayNightRate = Rate(unitMinutes = 60L, unitYen = 200, capRule = Capped(1800))
  val weekendDayRate = Rate(unitMinutes = 60L, unitYen = 200, capRule = Capped(1800))
  val weekendNightRate = Rate(unitMinutes = 60L, unitYen = 100, capRule = Capped(900))

  val calendar = PricingCalendar(
    zoneId = ZoneId.of("Asia/Tokyo"),
    freeMinutes = 5L,
    weekdayBands = Vector(
      TimeBand(dayBand, dayStart, dayEnd, weekdayDayRate),
      TimeBand(nightBand, dayEnd, dayStart, weekdayNightRate)
    ),
    weekendBands = Vector(
      TimeBand(dayBand, dayStart, dayEnd, weekendDayRate),
      TimeBand(nightBand, dayEnd, dayStart, weekendNightRate)
    )
  )

  val freeMinutes: Long = calendar.freeMinutes
  val pricingZone: ZoneId = calendar.zoneId

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
    var periodBand = bandAt(current, zoneId)
    var periodMinutes = 0L

    while (current.isBefore(end)) {
      val boundary = nextBoundary(current, zoneId)
      val segmentEnd = if (boundary.isBefore(end)) boundary else end
      val segmentMinutes = calcMinutes(current, segmentEnd)
      if (segmentMinutes > 0) periodMinutes += segmentMinutes
      current = segmentEnd

      val nextBandOpt = Option.when(current.isBefore(end))(bandAt(current, zoneId))
      if (nextBandOpt.forall(_ != periodBand)) {
        totalFee += feeForBandSegment(periodMinutes, periodBand.rate)
        periodMinutes = 0L
        nextBandOpt.foreach(nextBand => periodBand = nextBand)
      }
    }
    totalFee
  }

  private def feeForBandSegment(minutes: Long, rate: Rate): Int = {
    val units = ((minutes + rate.unitMinutes - 1) / rate.unitMinutes).toInt
    val rawFee = units * rate.unitYen
    rate.applyCap(rawFee)
  }

  private def formatRate(rate: Rate): String = {
    val cap = rate.capRule match {
      case Uncapped => "(上限なし)"
      case Capped(maxYen) => s"(最大${maxYen}円)"
    }
    s"${rate.unitMinutes}分ごと${rate.unitYen}円$cap"
  }

  private def bandAt(at: Instant, zoneId: ZoneId): TimeBand = {
    val zdt = ZonedDateTime.ofInstant(at, zoneId)
    val bands = calendar.bandsAt(zdt.getDayOfWeek)
    bands.find(_.contains(zdt.toLocalTime)).getOrElse(
      throw new IllegalStateException(s"No matching time band at $zdt")
    )
  }

  private def nextBoundary(at: Instant, zoneId: ZoneId): Instant = {
    val zdt = ZonedDateTime.ofInstant(at, zoneId)
    val bandBoundary = bandAt(at, zoneId).nextBoundary(zdt)
    val nextMidnight = zdt.toLocalDate.plusDays(1).atStartOfDay(zoneId)
    if (bandBoundary.isBefore(nextMidnight.toInstant)) bandBoundary else nextMidnight.toInstant
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
