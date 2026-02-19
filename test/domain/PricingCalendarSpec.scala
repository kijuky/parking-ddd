package domain

import java.time.{LocalTime, ZoneId}
import munit.FunSuite

class PricingCalendarSpec extends FunSuite {

  private val zone = ZoneId.of("Asia/Tokyo")
  private val dayStart = LocalTime.of(9, 0)
  private val dayEnd = LocalTime.of(18, 0)
  private def yen(value: Int): Money = Money.unsafe(value)

  private val dayRate = Rate(30L, yen(200), Uncapped)
  private val nightRate = Rate(60L, yen(200), Capped(yen(1800)))

  private val validWeekdayBands = Vector(
    TimeBand("day", dayStart, dayEnd, dayRate),
    TimeBand("night", dayEnd, dayStart, nightRate)
  )
  private val validWeekendBands = validWeekdayBands

  test("PricingCalendar.validate: weekday bands が空なら InvalidPricingCalendar") {
    val calendar = PricingCalendar(
      zoneId = zone,
      freeMinutes = 5L,
      weekdayBands = Vector.empty,
      weekendBands = validWeekendBands
    )

    val result = calendar.validate
    assert(result.isLeft)
    assert(result.left.toOption.exists {
      case InvalidPricingCalendar(details) => details.contains("weekday bands are empty")
      case _ => false
    })
  }

  test("PricingCalendar.validate: 時間帯が重複していれば InvalidPricingCalendar") {
    val overlappedWeekdayBands = Vector(
      TimeBand("day", dayStart, dayEnd, dayRate),
      TimeBand("overlap", LocalTime.of(17, 0), LocalTime.of(20, 0), dayRate),
      TimeBand("night", LocalTime.of(20, 0), dayStart, nightRate)
    )
    val calendar = PricingCalendar(
      zoneId = zone,
      freeMinutes = 5L,
      weekdayBands = overlappedWeekdayBands,
      weekendBands = validWeekendBands
    )

    val result = calendar.validate
    assert(result.isLeft)
    assert(result.left.toOption.exists {
      case InvalidPricingCalendar(details) =>
        details.contains("weekday bands must cover each minute exactly once")
      case _ => false
    })
  }

  test("PricingCalendar.validate: 時間帯に穴があれば InvalidPricingCalendar") {
    val gappedWeekdayBands = Vector(
      TimeBand("day-1", LocalTime.of(9, 0), LocalTime.of(12, 0), dayRate),
      TimeBand("day-2", LocalTime.of(13, 0), LocalTime.of(18, 0), dayRate),
      TimeBand("night", LocalTime.of(18, 0), LocalTime.of(9, 0), nightRate)
    )
    val calendar = PricingCalendar(
      zoneId = zone,
      freeMinutes = 5L,
      weekdayBands = gappedWeekdayBands,
      weekendBands = validWeekendBands
    )

    val result = calendar.validate
    assert(result.isLeft)
    assert(result.left.toOption.exists {
      case InvalidPricingCalendar(details) =>
        details.contains("weekday bands must cover each minute exactly once")
      case _ => false
    })
  }

  test("PricingCalendar.validate: 昼夜2帯で24時間を一意に覆っていれば成功") {
    val calendar = PricingCalendar(
      zoneId = zone,
      freeMinutes = 5L,
      weekdayBands = validWeekdayBands,
      weekendBands = validWeekendBands
    )

    assertEquals(calendar.validate, Right(calendar))
  }
}
