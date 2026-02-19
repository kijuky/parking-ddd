package app

import domain._
import infra._
import java.time.Instant
import munit.FunSuite

class ParkingAppSpec extends FunSuite {

  test("出庫時に CarEntered が無ければ CorruptedEventStream を投げる") {
  val store = new InMemoryEventStore
  val registry = new SlotRegistry
  val app = new ParkingApp(store, registry)
  val slot = SlotNo.from(1).getOrElse(fail("valid slot expected"))
  val sessionId = "broken-session"

  registry.park(slot, sessionId)
  store.append(FeeCalculated(sessionId, slot, 0L, 0))

  intercept[CorruptedEventStream] {
      app.handle(Pressed(slot, Instant.parse("2026-02-19T10:00:00Z")))
  }
  }
}
