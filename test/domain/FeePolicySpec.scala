package domain

import java.time.{Instant, ZoneId}
import munit.FunSuite

class FeePolicySpec extends FunSuite {

  private val zone = ZoneId.of("Asia/Tokyo")
  private val weekdayParkedAt = Instant.parse("2026-02-19T09:00:00+09:00") // Thu

  private def weekdayAt(time: String): Instant =
    Instant.parse(s"2026-02-19T$time:00+09:00")

  test("最初の5分は無料") {
    assertEquals(FeePolicy.calcFeeYen(weekdayParkedAt, weekdayAt("09:05"), zone), 0)
  }

  test("平日昼: 9:06 から 200円") {
    assertEquals(FeePolicy.calcFeeYen(weekdayParkedAt, weekdayAt("09:06"), zone), 200)
  }

  test("平日昼: 9:31 から 400円") {
    assertEquals(FeePolicy.calcFeeYen(weekdayParkedAt, weekdayAt("09:31"), zone), 400)
  }

  test("平日夜: 60分ごと 200円") {
    val start = Instant.parse("2026-02-19T18:00:00+09:00")
    assertEquals(FeePolicy.calcFeeYen(start, Instant.parse("2026-02-19T19:00:00+09:00"), zone), 200)
    assertEquals(FeePolicy.calcFeeYen(start, Instant.parse("2026-02-19T19:01:00+09:00"), zone), 400)
  }

  test("平日夜は最大1800円で頭打ち") {
    val start = Instant.parse("2026-02-19T18:00:00+09:00")
    val end = Instant.parse("2026-02-20T09:00:00+09:00")
    assertEquals(FeePolicy.calcFeeYen(start, end, zone), 1800)
  }

  test("休日昼: 60分ごと 200円、最大1800円") {
    val start = Instant.parse("2026-02-21T09:00:00+09:00") // Sat
    assertEquals(FeePolicy.calcFeeYen(start, Instant.parse("2026-02-21T10:00:00+09:00"), zone), 200)
    assertEquals(FeePolicy.calcFeeYen(start, Instant.parse("2026-02-21T10:01:00+09:00"), zone), 400)
    assertEquals(FeePolicy.calcFeeYen(start, Instant.parse("2026-02-21T18:00:00+09:00"), zone), 1800)
  }

  test("休日夜: 60分ごと 100円、最大900円") {
    val start = Instant.parse("2026-02-21T18:00:00+09:00") // Sat
    assertEquals(FeePolicy.calcFeeYen(start, Instant.parse("2026-02-21T19:00:00+09:00"), zone), 100)
    assertEquals(FeePolicy.calcFeeYen(start, Instant.parse("2026-02-21T19:01:00+09:00"), zone), 200)
    assertEquals(FeePolicy.calcFeeYen(start, Instant.parse("2026-02-22T09:00:00+09:00"), zone), 900)
  }

  test("金曜夜から土曜朝は 0:00 を跨いで平日夜→休日夜で合算する") {
    val start = Instant.parse("2026-02-20T23:30:00+09:00") // Fri
    val end = Instant.parse("2026-02-21T00:30:00+09:00") // Sat
    assertEquals(FeePolicy.calcFeeYen(start, end, zone), 300)
  }

  test("料金説明文を生成できる") {
    assertEquals(
      FeePolicy.pricingSummary,
      "最初の5分無料 / 平日 昼(9:00-18:00) 30分ごと200円(上限なし) / 平日 夜(18:00-翌9:00) 60分ごと200円(最大1800円) / 休日(土日) 昼(9:00-18:00) 60分ごと200円(最大1800円) / 休日(土日) 夜(18:00-翌9:00) 60分ごと100円(最大900円)"
    )
  }
}
