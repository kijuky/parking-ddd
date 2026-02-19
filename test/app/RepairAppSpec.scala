package app

import domain._
import infra._
import java.time.Instant
import munit.FunSuite

class RepairAppSpec extends FunSuite {
  private def yen(value: Int): Money = Money.unsafe(value)

  test("RequestRepair で DataRepairRequested を追記する") {
    val store = new InMemoryEventStore
    val app = new RepairApp(store)
    val slot = SlotNo.from(1).getOrElse(fail("valid slot expected"))
    val at = Instant.parse("2026-02-19T10:00:00+09:00")

    val result = app.handle(RequestRepair("session-1", slot, "missing CarEntered", at))
    val event = result.getOrElse(fail("expected successful repair request"))

    assertEquals(event, DataRepairRequested("session-1", slot, "missing CarEntered", at))
    assertEquals(store.load("session-1").lastOption, Some(event))
  }

  test("RequestRepair の reason が空なら DomainError を返す") {
    val store = new InMemoryEventStore
    val app = new RepairApp(store)
    val slot = SlotNo.from(1).getOrElse(fail("valid slot expected"))
    val at = Instant.parse("2026-02-19T10:00:00+09:00")

    val result = app.handle(RequestRepair("session-1", slot, "   ", at))

    assertEquals(result, Left(InvalidRepairRequest("reason must not be empty")))
    assertEquals(store.load("session-1"), Vector.empty)
  }

  test("ReconcileSession で SessionReconciled を追記する") {
    val store = new InMemoryEventStore
    val app = new RepairApp(store)
    val slot = SlotNo.from(2).getOrElse(fail("valid slot expected"))
    val enteredAt = Instant.parse("2026-02-19T10:00:00+09:00")
    val exitedAt = Instant.parse("2026-02-19T10:31:00+09:00")
    val at = Instant.parse("2026-02-19T10:35:00+09:00")

    val result = app.handle(
      ReconcileSession(
        sessionId = "session-2",
        slot = slot,
        correctedEnteredAt = enteredAt,
        correctedExitedAt = exitedAt,
        note = "operator fixed from CCTV log",
        at = at
      )
    )
    val event = result.getOrElse(fail("expected successful reconciliation"))

    assertEquals(
      event,
      SessionReconciled("session-2", slot, enteredAt, exitedAt, 31L, yen(400), "operator fixed from CCTV log", at)
    )
    assertEquals(store.load("session-2").lastOption, Some(event))
  }
}
