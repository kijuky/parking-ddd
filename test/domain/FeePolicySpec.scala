package domain

import java.time.{Instant, ZoneId}
import munit.FunSuite

class FeePolicySpec extends FunSuite {

  private val zone = ZoneId.of("Asia/Tokyo")
  private val parkedAt = Instant.parse("2026-02-19T09:00:00+09:00")

  private def at(time: String): Instant =
    Instant.parse(s"2026-02-19T$time:00+09:00")

  test("最初の5分は無料") {
    assertEquals(FeePolicy.calcFeeYen(parkedAt, at("09:05"), zone), 0)
  }

  test("昼: 9:06 から 200円") {
    assertEquals(FeePolicy.calcFeeYen(parkedAt, at("09:06"), zone), 200)
  }

  test("昼: 9:30 までは 200円") {
    assertEquals(FeePolicy.calcFeeYen(parkedAt, at("09:30"), zone), 200)
  }

  test("昼: 9:31 から 400円") {
    assertEquals(FeePolicy.calcFeeYen(parkedAt, at("09:31"), zone), 400)
  }

  test("昼: 10:01 から 600円") {
    assertEquals(FeePolicy.calcFeeYen(parkedAt, at("10:01"), zone), 600)
  }

  test("夜: 60分ごと 200円") {
    val nightStart = Instant.parse("2026-02-19T18:00:00+09:00")
    assertEquals(FeePolicy.calcFeeYen(nightStart, Instant.parse("2026-02-19T18:05:00+09:00"), zone), 0)
    assertEquals(FeePolicy.calcFeeYen(nightStart, Instant.parse("2026-02-19T18:06:00+09:00"), zone), 200)
    assertEquals(FeePolicy.calcFeeYen(nightStart, Instant.parse("2026-02-19T19:00:00+09:00"), zone), 200)
    assertEquals(FeePolicy.calcFeeYen(nightStart, Instant.parse("2026-02-19T19:01:00+09:00"), zone), 400)
  }

  test("昼は最大料金なし: 9時間で3600円") {
    val dayStart = Instant.parse("2026-02-19T09:00:00+09:00")
    val dayEnd = Instant.parse("2026-02-19T18:00:00+09:00")
    assertEquals(FeePolicy.calcFeeYen(dayStart, dayEnd, zone), 3600)
  }

  test("夜は最大1800円で頭打ち") {
    val nightStart = Instant.parse("2026-02-19T18:00:00+09:00")
    val nextMorning = Instant.parse("2026-02-20T09:00:00+09:00")
    assertEquals(FeePolicy.calcFeeYen(nightStart, nextMorning, zone), 1800)
  }

  test("昼夜昼で各時間帯の料金を合算する") {
    val start = Instant.parse("2026-02-19T09:00:00+09:00")
    val end = Instant.parse("2026-02-20T10:00:00+09:00")
    assertEquals(FeePolicy.calcFeeYen(start, end, zone), 5800)
  }

  test("料金説明文を生成できる") {
    assertEquals(
      FeePolicy.pricingSummary,
      "最初の5分無料 / 昼(9:00-18:00) 30分ごと200円(上限なし) / 夜(18:00-翌9:00) 60分ごと200円(最大1800円)"
    )
  }
}
