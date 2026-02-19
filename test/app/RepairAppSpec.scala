package app

import domain._
import infra._
import java.time.Instant
import munit.FunSuite

class RepairAppSpec extends FunSuite {

  test("RequestRepair で DataRepairRequested を追記する") {
  val store = new InMemoryEventStore
  val app = new RepairApp(store)
  val slot = SlotNo.from(1).getOrElse(fail("valid slot expected"))
  val at = Instant.parse("2026-02-19T10:00:00Z")

  val event = app.handle(RequestRepair("session-1", slot, "missing CarEntered", at))

  assertEquals(event, DataRepairRequested("session-1", slot, "missing CarEntered", at))
  assertEquals(store.load("session-1").lastOption, Some(event))
  }

  test("ReconcileSession で SessionReconciled を追記する") {
  val store = new InMemoryEventStore
  val app = new RepairApp(store)
  val slot = SlotNo.from(2).getOrElse(fail("valid slot expected"))
  val enteredAt = Instant.parse("2026-02-19T10:00:00Z")
  val exitedAt = Instant.parse("2026-02-19T10:31:00Z")
  val at = Instant.parse("2026-02-19T10:35:00Z")

  val event = app.handle(
      ReconcileSession(
    sessionId = "session-2",
    slot = slot,
    correctedEnteredAt = enteredAt,
    correctedExitedAt = exitedAt,
    note = "operator fixed from CCTV log",
    at = at
      )
  )

  assertEquals(
      event,
      SessionReconciled("session-2", slot, enteredAt, exitedAt, 31L, 400, "operator fixed from CCTV log", at)
  )
  assertEquals(store.load("session-2").lastOption, Some(event))
  }
}
