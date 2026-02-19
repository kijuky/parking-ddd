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

sealed trait DomainError { def message: String }
case class CorruptedEventStream(sessionId: SessionId, slot: SlotNo, details: String) extends DomainError {
  override def message: String = s"Corrupted event stream: sessionId=$sessionId slot=$slot details=$details"
}
case class InvalidPricingCalendar(details: String) extends DomainError {
  override def message: String = s"Invalid pricing calendar: $details"
}
case class MissingTimeBand(at: Instant, zoneId: ZoneId, details: String) extends DomainError {
  override def message: String =
    s"Missing time band at ${ZonedDateTime.ofInstant(at, zoneId)} in zone=$zoneId details=$details"
}
case class InvalidRepairRequest(details: String) extends DomainError {
  override def message: String = s"Invalid repair request: $details"
}

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

  def validate: Either[DomainError, PricingCalendar] =
    for {
      _ <- validateBands("weekday", weekdayBands)
      _ <- validateBands("weekend", weekendBands)
    } yield this

  private def validateBands(label: String, bands: Vector[TimeBand]): Either[DomainError, Unit] = {
    if (bands.isEmpty) return Left(InvalidPricingCalendar(s"$label bands are empty"))

    // Lightweight startup-time validation: ensure each minute in a day maps to exactly one band.
    val invalidMinute = (0 until 24 * 60).find { minute =>
      val t = LocalTime.of(minute / 60, minute % 60)
      bands.count(_.contains(t)) != 1
    }

    invalidMinute match {
      case Some(minute) =>
        val t = LocalTime.of(minute / 60, minute % 60)
        val matched = bands.filter(_.contains(t)).map(_.name)
        Left(InvalidPricingCalendar(s"$label bands must cover each minute exactly once (time=$t, matched=$matched)"))
      case None =>
        Right(())
    }
  }
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
  private val validatedCalendar: Either[DomainError, PricingCalendar] = calendar.validate

  def pricingSummary: Either[DomainError, String] =
    for {
      weekdayDay <- bandByName(calendar.weekdayBands, dayBand, "weekday")
      weekdayNight <- bandByName(calendar.weekdayBands, nightBand, "weekday")
      weekendDay <- bandByName(calendar.weekendBands, dayBand, "weekend")
      weekendNight <- bandByName(calendar.weekendBands, nightBand, "weekend")
    } yield
      s"最初の${freeMinutes}分無料 / 平日 ${formatBand(weekdayDay)} / 平日 ${formatBand(weekdayNight)} / 休日(土日) ${formatBand(weekendDay)} / 休日(土日) ${formatBand(weekendNight)}"

  def calcMinutes(start: Instant, end: Instant): Long =
    Math.max(0L, Duration.between(start, end).toMinutes)

  def calcFeeYen(start: Instant, end: Instant): Either[DomainError, Int] =
    calcFeeYen(start, end, pricingZone)

  def calcFeeYen(start: Instant, end: Instant, zoneId: ZoneId): Either[DomainError, Int] =
    validatedCalendar.flatMap(calcFeeYenWithCalendar(start, end, zoneId, _))

  private def calcFeeYenWithCalendar(
    start: Instant,
    end: Instant,
    zoneId: ZoneId,
    calendar: PricingCalendar
  ): Either[DomainError, Int] = {
    val totalMinutes = calcMinutes(start, end)
    if (totalMinutes <= freeMinutes) Right(0)
    else if (!start.isBefore(end)) Right(0)
    else
      bandAt(start, zoneId, calendar).flatMap { initialBand =>
        loop(start, end, zoneId, calendar, initialBand, 0L, 0)
      }
  }

  private def loop(
    current: Instant,
    end: Instant,
    zoneId: ZoneId,
    calendar: PricingCalendar,
    periodBand: TimeBand,
    periodMinutes: Long,
    totalFee: Int
  ): Either[DomainError, Int] = {
    if (!current.isBefore(end)) Right(totalFee + feeForBandSegment(periodMinutes, periodBand.rate))
    else {
      val boundary = nextBoundary(current, zoneId, periodBand)
      val segmentEnd = if (boundary.isBefore(end)) boundary else end
      val segmentMinutes = calcMinutes(current, segmentEnd)
      val updatedMinutes = periodMinutes + segmentMinutes

      if (!segmentEnd.isBefore(end)) Right(totalFee + feeForBandSegment(updatedMinutes, periodBand.rate))
      else
        bandAt(segmentEnd, zoneId, calendar).flatMap { nextBand =>
          if (nextBand == periodBand)
            loop(segmentEnd, end, zoneId, calendar, periodBand, updatedMinutes, totalFee)
          else
            loop(
              segmentEnd,
              end,
              zoneId,
              calendar,
              nextBand,
              0L,
              totalFee + feeForBandSegment(updatedMinutes, periodBand.rate)
            )
        }
    }
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

  private def formatBand(band: TimeBand): String = {
    val label = band.name match {
      case `dayBand` => "昼"
      case `nightBand` => "夜"
      case other => other
    }
    s"$label(${formatTime(band.start)}-${formatEndTime(band.start, band.end)}) ${formatRate(band.rate)}"
  }

  private def formatTime(t: LocalTime): String = f"${t.getHour}%d:${t.getMinute}%02d"

  private def formatEndTime(start: LocalTime, end: LocalTime): String =
    if (end.isBefore(start)) s"翌${formatTime(end)}" else formatTime(end)

  private def bandByName(bands: Vector[TimeBand], name: String, label: String): Either[DomainError, TimeBand] =
    bands.find(_.name == name).toRight(InvalidPricingCalendar(s"$label band '$name' is missing"))

  private def bandAt(at: Instant, zoneId: ZoneId, calendar: PricingCalendar): Either[DomainError, TimeBand] = {
    val zdt = ZonedDateTime.ofInstant(at, zoneId)
    val bands = calendar.bandsAt(zdt.getDayOfWeek)
    bands
      .find(_.contains(zdt.toLocalTime))
      .toRight(MissingTimeBand(at, zoneId, s"matched bands for ${zdt.toLocalTime} not found"))
  }

  private def nextBoundary(at: Instant, zoneId: ZoneId, band: TimeBand): Instant = {
    val zdt = ZonedDateTime.ofInstant(at, zoneId)
    val bandBoundary = band.nextBoundary(zdt)
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
  ): Either[DomainError, SessionReconciled] =
    FeePolicy.calcFeeYen(correctedEnteredAt, correctedExitedAt).map { feeYen =>
      val minutes = FeePolicy.calcMinutes(correctedEnteredAt, correctedExitedAt)
      SessionReconciled(sessionId, slot, correctedEnteredAt, correctedExitedAt, minutes, feeYen, note, at)
    }
}

// Aggregate (per session)
case class ParkingSession(sessionId: SessionId, slot: SlotNo, enteredAt: Instant) {
  def exit(at: Instant): Either[DomainError, (CarExited, FeeCalculated)] =
    FeePolicy.calcFeeYen(enteredAt, at).map { fee =>
      val minutes = FeePolicy.calcMinutes(enteredAt, at)
      (CarExited(sessionId, slot, at), FeeCalculated(sessionId, slot, minutes, fee))
    }
}
