package domain

import java.time.Instant
import munit.FunSuite

class ParkingSessionSpec extends FunSuite {
  private def yen(value: Int): Money = Money.unsafe(value)

  test("出庫時に CarExited と FeeCalculated を生成する") {
    val enteredAt = Instant.parse("2026-02-19T10:00:00+09:00")
    val exitedAt = Instant.parse("2026-02-19T10:31:00+09:00")
    val slot = SlotNo.from(1).getOrElse(fail("valid slot expected"))
    val session = ParkingSession("session-1", slot, enteredAt)

    val result = session.exit(exitedAt)
    val (exited, fee) = result.getOrElse(fail("expected successful exit"))

    assertEquals(exited, CarExited("session-1", slot, exitedAt))
    assertEquals(fee, FeeCalculated("session-1", slot, 31L, yen(400)))
  }
}
