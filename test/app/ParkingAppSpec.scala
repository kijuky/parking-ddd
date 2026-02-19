package app

import domain._
import infra._
import java.time.Instant
import java.util.concurrent.{CountDownLatch, TimeUnit}
import munit.FunSuite
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class ParkingAppSpec extends FunSuite {
  private def yen(value: Int): Money = Money.unsafe(value)

  private def newApp(): (InMemoryEventStore, SlotRegistry, ParkingApp) = {
    val store = new InMemoryEventStore
    val registry = new SlotRegistry(store)
    val app = new ParkingApp(store, registry)
    (store, registry, app)
  }

  private final class ConflictStore(conflictOn: Event => Boolean) extends InMemoryEventStore {
    override def compareAndAppend(
      sessionId: SessionId,
      expectedVersion: Long,
      newEvents: Vector[Event]
    ): Either[VersionConflict, Long] =
      if (newEvents.exists(conflictOn)) Left(VersionConflict(sessionId, expectedVersion, expectedVersion + 1))
      else super.compareAndAppend(sessionId, expectedVersion, newEvents)
  }

  test("入庫→出庫要求→分割入金→出庫確定ができる") {
    val (_, registry, app) = newApp()
    val slot = SlotNo.from(1).getOrElse(fail("valid slot expected"))

    val enterAt = Instant.parse("2026-02-19T09:00:00+09:00")
    val requestAt = Instant.parse("2026-02-19T09:06:00+09:00")

    val entered = app.handle(Pressed(slot, enterAt))
    assertEquals(entered, Right(Entered(slot)))

    val requested = app.handle(Pressed(slot, requestAt))
    assertEquals(requested, Right(AwaitingPayment(slot, yen(200), yen(0), yen(200))))

    val p1 = app.insertMoney(slot, yen(100), Instant.parse("2026-02-19T09:07:00+09:00"), "cmd-insert-1")
    assertEquals(p1, Right(PaymentRemaining(yen(200), yen(100), yen(100))))

    val p2 = app.insertMoney(slot, yen(150), Instant.parse("2026-02-19T09:08:00+09:00"), "cmd-insert-2")
    assertEquals(p2, Right(PaymentSettled(yen(200), yen(250), yen(50))))

    val exited = app.finalizeExit(slot, Instant.parse("2026-02-19T09:09:00+09:00"), "cmd-exit-1")
    assertEquals(exited, Right(Exited(slot)))
    assertEquals(registry.sessionAt(slot), Right(None))
  }

  test("5分以内は0円で支払い完了扱いになり出庫確定できる") {
    val (_, registry, app) = newApp()
    val slot = SlotNo.from(3).getOrElse(fail("valid slot expected"))

    val enterAt = Instant.parse("2026-02-19T09:00:00+09:00")
    val requestAt = Instant.parse("2026-02-19T09:05:00+09:00")

    assertEquals(app.handle(Pressed(slot, enterAt)), Right(Entered(slot)))
    assertEquals(app.handle(Pressed(slot, requestAt)), Right(AwaitingPayment(slot, yen(0), yen(0), yen(0))))
    assertEquals(app.finalizeExit(slot, Instant.parse("2026-02-19T09:05:10+09:00"), "cmd-exit-free"), Right(Exited(slot)))
    assertEquals(registry.sessionAt(slot), Right(None))
  }

  test("出庫時に CarEntered が無ければ DomainError を返す") {
    val store = new InMemoryEventStore
    val slot = SlotNo.from(1).getOrElse(fail("valid slot expected"))
    val sessionId = "broken-session"
    val registry = new SlotRegistry(store) {
      override def sessionAt(s: SlotNo): Either[DomainError, Option[SessionId]] =
        if (s == slot) Right(Some(sessionId)) else Right(None)
    }
    val app = new ParkingApp(store, registry)

    store.append(FeeCalculated(sessionId, slot, 0L, yen(0)))

    val result = app.handle(Pressed(slot, Instant.parse("2026-02-19T10:00:00Z")))
    assertEquals(result, Left(CorruptedEventStream(sessionId, slot, "CarEntered is missing")))
  }

  test("出庫時に CarEntered が重複していれば DomainError を返す") {
    val (store, _, app) = newApp()
    val slot = SlotNo.from(2).getOrElse(fail("valid slot expected"))
    val sessionId = "duplicated-entered-session"

    store.append(CarEntered(sessionId, slot, Instant.parse("2026-02-19T09:00:00Z")))
    store.append(CarEntered(sessionId, slot, Instant.parse("2026-02-19T09:01:00Z")))

    val result = app.handle(Pressed(slot, Instant.parse("2026-02-19T10:00:00Z")))
    assertEquals(result, Left(CorruptedEventStream(sessionId, slot, "Multiple CarEntered found")))
  }

  test("破損イベント列で paidYen が totalYen を超える場合は DomainError を返す") {
    val store = new InMemoryEventStore
    val registry = new SlotRegistry(store)
    val app = new ParkingApp(store, registry)
    val slot = SlotNo.from(3).getOrElse(fail("valid slot expected"))
    val sessionId = "broken-payment-session"

    store.append(CarEntered(sessionId, slot, Instant.parse("2026-02-19T09:00:00Z")))
    store.append(ExitRequested(sessionId, slot, yen(100), Instant.parse("2026-02-19T09:10:00Z")))
    store.append(
      MoneyInserted(
        sessionId,
        slot,
        amountYen = yen(200),
        totalYen = yen(100),
        paidYenAfter = yen(200),
        remainingYenAfter = yen(0),
        at = Instant.parse("2026-02-19T09:11:00Z")
      )
    )

    val result = app.handle(Pressed(slot, Instant.parse("2026-02-19T09:12:00Z")))
    assertEquals(
      result,
      Left(
        CorruptedEventStream(
          sessionId,
          slot,
          "invalid payment balance: Invalid money: money must be non-negative, but was -100"
        )
      )
    )
  }

  test("出庫要求前に finalizeExit すると DomainError を返す") {
    val (_, _, app) = newApp()
    val slot = SlotNo.from(4).getOrElse(fail("valid slot expected"))

    assertEquals(app.handle(Pressed(slot, Instant.parse("2026-02-19T09:00:00+09:00"))), Right(Entered(slot)))
    val result = app.finalizeExit(slot, Instant.parse("2026-02-19T09:01:00+09:00"), "cmd-exit-before-request")
    assertEquals(result, Left(InvalidPaymentOperation("exit was not requested")))
  }

  test("出庫要求前に insertMoney すると DomainError を返す") {
    val (_, _, app) = newApp()
    val slot = SlotNo.from(5).getOrElse(fail("valid slot expected"))

    assertEquals(app.handle(Pressed(slot, Instant.parse("2026-02-19T09:00:00+09:00"))), Right(Entered(slot)))
    val result = app.insertMoney(slot, yen(100), Instant.parse("2026-02-19T09:01:00+09:00"), "cmd-insert-before-request")
    assertEquals(result, Left(InvalidPaymentOperation("exit was not requested")))
  }

  test("入金額が0以下なら DomainError を返す") {
    val (_, _, app) = newApp()
    val slot = SlotNo.from(6).getOrElse(fail("valid slot expected"))

    val result = app.insertMoney(slot, yen(0), Instant.parse("2026-02-19T09:01:00+09:00"), "cmd-invalid-amount")
    assertEquals(result, Left(InvalidPaymentOperation("inserted amount must be positive")))
  }

  test("決済完了後に追加で insertMoney すると DomainError を返す") {
    val (_, _, app) = newApp()
    val slot = SlotNo.from(7).getOrElse(fail("valid slot expected"))

    assertEquals(app.handle(Pressed(slot, Instant.parse("2026-02-19T09:00:00+09:00"))), Right(Entered(slot)))
    assertEquals(
      app.handle(Pressed(slot, Instant.parse("2026-02-19T09:06:00+09:00"))),
      Right(AwaitingPayment(slot, yen(200), yen(0), yen(200)))
    )
    assertEquals(
      app.insertMoney(slot, yen(200), Instant.parse("2026-02-19T09:07:00+09:00"), "cmd-insert-settle-1"),
      Right(PaymentSettled(yen(200), yen(200), yen(0)))
    )

    val result = app.insertMoney(slot, yen(100), Instant.parse("2026-02-19T09:08:00+09:00"), "cmd-insert-after-settled")
    assertEquals(result, Left(InvalidPaymentOperation("payment is already settled")))
  }

  test("insertMoney は同一 commandId の再送で同一結果を返す（未完了決済）") {
    val (_, _, app) = newApp()
    val slot = SlotNo.from(8).getOrElse(fail("valid slot expected"))
    val commandId = "cmd-retry-insert-remaining"

    assertEquals(app.handle(Pressed(slot, Instant.parse("2026-02-19T09:00:00+09:00"))), Right(Entered(slot)))
    assertEquals(
      app.handle(Pressed(slot, Instant.parse("2026-02-19T09:06:00+09:00"))),
      Right(AwaitingPayment(slot, yen(200), yen(0), yen(200)))
    )

    val first = app.insertMoney(slot, yen(100), Instant.parse("2026-02-19T09:07:00+09:00"), commandId)
    val retry = app.insertMoney(slot, yen(100), Instant.parse("2026-02-19T09:08:00+09:00"), commandId)
    assertEquals(first, Right(PaymentRemaining(yen(200), yen(100), yen(100))))
    assertEquals(retry, first)

    val settle = app.insertMoney(slot, yen(100), Instant.parse("2026-02-19T09:09:00+09:00"), "cmd-after-retry")
    assertEquals(settle, Right(PaymentSettled(yen(200), yen(200), yen(0))))
  }

  test("insertMoney は同一 commandId で金額が異なる再送を拒否する") {
    val (_, _, app) = newApp()
    val slot = SlotNo.from(9).getOrElse(fail("valid slot expected"))
    val commandId = "cmd-retry-conflict"

    assertEquals(app.handle(Pressed(slot, Instant.parse("2026-02-19T09:00:00+09:00"))), Right(Entered(slot)))
    assertEquals(
      app.handle(Pressed(slot, Instant.parse("2026-02-19T09:06:00+09:00"))),
      Right(AwaitingPayment(slot, yen(200), yen(0), yen(200)))
    )
    assertEquals(
      app.insertMoney(slot, yen(100), Instant.parse("2026-02-19T09:07:00+09:00"), commandId),
      Right(PaymentRemaining(yen(200), yen(100), yen(100)))
    )

    val retry = app.insertMoney(slot, yen(50), Instant.parse("2026-02-19T09:08:00+09:00"), commandId)
    assertEquals(retry, Left(InvalidPaymentOperation("commandId was already used with different amount")))
  }

  test("insertMoney は決済完了後でも同一 commandId の金額不一致再送を拒否する") {
    val (_, _, app) = newApp()
    val slot = SlotNo.from(1).getOrElse(fail("valid slot expected"))
    val commandId = "cmd-retry-conflict-after-settled"

    assertEquals(app.handle(Pressed(slot, Instant.parse("2026-02-19T09:00:00+09:00"))), Right(Entered(slot)))
    assertEquals(
      app.handle(Pressed(slot, Instant.parse("2026-02-19T09:06:00+09:00"))),
      Right(AwaitingPayment(slot, yen(200), yen(0), yen(200)))
    )
    assertEquals(
      app.insertMoney(slot, yen(200), Instant.parse("2026-02-19T09:07:00+09:00"), commandId),
      Right(PaymentSettled(yen(200), yen(200), yen(0)))
    )

    val retry = app.insertMoney(slot, yen(100), Instant.parse("2026-02-19T09:08:00+09:00"), commandId)
    assertEquals(retry, Left(InvalidPaymentOperation("commandId was already used with different amount")))
  }

  test("finalizeExit は出庫後の再送で slot not occupied を返す") {
    val (_, _, app) = newApp()
    val slot = SlotNo.from(1).getOrElse(fail("valid slot expected"))
    val commandId = "cmd-retry-finalize"

    assertEquals(app.handle(Pressed(slot, Instant.parse("2026-02-19T09:00:00+09:00"))), Right(Entered(slot)))
    assertEquals(
      app.handle(Pressed(slot, Instant.parse("2026-02-19T09:05:00+09:00"))),
      Right(AwaitingPayment(slot, yen(0), yen(0), yen(0)))
    )

    val first = app.finalizeExit(slot, Instant.parse("2026-02-19T09:05:10+09:00"), commandId)
    val retry = app.finalizeExit(slot, Instant.parse("2026-02-19T09:05:20+09:00"), commandId)
    assertEquals(first, Right(Exited(slot)))
    assertEquals(retry, Left(InvalidPaymentOperation("slot 1 is not occupied")))
  }

  test("同一slotの別セッションで同じ commandId を使っても前セッション結果を誤再利用しない") {
    val (_, _, app) = newApp()
    val slot = SlotNo.from(5).getOrElse(fail("valid slot expected"))
    val reusedId = "cmd-reused-across-sessions"

    assertEquals(app.handle(Pressed(slot, Instant.parse("2026-02-19T09:00:00+09:00"))), Right(Entered(slot)))
    assertEquals(
      app.handle(Pressed(slot, Instant.parse("2026-02-19T09:06:00+09:00"))),
      Right(AwaitingPayment(slot, yen(200), yen(0), yen(200)))
    )
    assertEquals(
      app.insertMoney(slot, yen(200), Instant.parse("2026-02-19T09:07:00+09:00"), reusedId),
      Right(PaymentSettled(yen(200), yen(200), yen(0)))
    )
    assertEquals(app.finalizeExit(slot, Instant.parse("2026-02-19T09:07:10+09:00"), "cmd-exit-first"), Right(Exited(slot)))

    assertEquals(app.handle(Pressed(slot, Instant.parse("2026-02-19T10:00:00+09:00"))), Right(Entered(slot)))
    assertEquals(
      app.handle(Pressed(slot, Instant.parse("2026-02-19T10:06:00+09:00"))),
      Right(AwaitingPayment(slot, yen(200), yen(0), yen(200)))
    )

    val result = app.insertMoney(slot, yen(100), Instant.parse("2026-02-19T10:07:00+09:00"), reusedId)
    assertEquals(result, Right(PaymentRemaining(yen(200), yen(100), yen(100))))
  }

  test("出庫要求をキャンセルすると入庫状態に戻り、再度出庫要求できる") {
    val (_, _, app) = newApp()
    val slot = SlotNo.from(2).getOrElse(fail("valid slot expected"))

    assertEquals(app.handle(Pressed(slot, Instant.parse("2026-02-19T09:00:00+09:00"))), Right(Entered(slot)))
    assertEquals(
      app.handle(Pressed(slot, Instant.parse("2026-02-19T09:06:00+09:00"))),
      Right(AwaitingPayment(slot, yen(200), yen(0), yen(200)))
    )
    assertEquals(app.cancelExitRequest(slot, Instant.parse("2026-02-19T09:06:30+09:00"), "input error"), Right(()))
    assertEquals(
      app.insertMoney(slot, yen(100), Instant.parse("2026-02-19T09:06:40+09:00"), "cmd-after-cancel"),
      Left(InvalidPaymentOperation("exit was not requested"))
    )
    assertEquals(
      app.handle(Pressed(slot, Instant.parse("2026-02-19T09:07:00+09:00"))),
      Right(AwaitingPayment(slot, yen(200), yen(0), yen(200)))
    )
  }

  test("insertMoney で楽観ロック競合したら DomainError を返す") {
    val store = new ConflictStore({
      case _: MoneyInserted => true
      case _ => false
    })
    val registry = new SlotRegistry(store)
    val app = new ParkingApp(store, registry)
    val slot = SlotNo.from(3).getOrElse(fail("valid slot expected"))

    assertEquals(app.handle(Pressed(slot, Instant.parse("2026-02-19T09:00:00+09:00"))), Right(Entered(slot)))
    assertEquals(
      app.handle(Pressed(slot, Instant.parse("2026-02-19T09:06:00+09:00"))),
      Right(AwaitingPayment(slot, yen(200), yen(0), yen(200)))
    )

    val result = app.insertMoney(slot, yen(100), Instant.parse("2026-02-19T09:07:00+09:00"), "cmd-conflict-insert")
    assertEquals(result, Left(InvalidPaymentOperation("concurrent update detected")))
  }

  test("finalizeExit で楽観ロック競合したら DomainError を返す") {
    val store = new ConflictStore({
      case _: CarExited => true
      case _ => false
    })
    val registry = new SlotRegistry(store)
    val app = new ParkingApp(store, registry)
    val slot = SlotNo.from(4).getOrElse(fail("valid slot expected"))

    assertEquals(app.handle(Pressed(slot, Instant.parse("2026-02-19T09:00:00+09:00"))), Right(Entered(slot)))
    assertEquals(app.handle(Pressed(slot, Instant.parse("2026-02-19T09:05:00+09:00"))), Right(AwaitingPayment(slot, yen(0), yen(0), yen(0))))

    val result = app.finalizeExit(slot, Instant.parse("2026-02-19T09:06:00+09:00"), "cmd-conflict-exit")
    assertEquals(result, Left(InvalidPaymentOperation("concurrent update detected")))
  }

  test("同時入庫は先勝ち固定で CarEntered が1件のみ記録される") {
    val (store, registry, app) = newApp()
    val slot = SlotNo.from(1).getOrElse(fail("valid slot expected"))
    val at = Instant.parse("2026-02-19T09:00:00+09:00")
    val ready = new CountDownLatch(2)
    val start = new CountDownLatch(1)

    def press(): Future[Either[DomainError, PressedResult]] = Future {
      ready.countDown()
      ready.await(3, TimeUnit.SECONDS)
      start.await(3, TimeUnit.SECONDS)
      app.handle(Pressed(slot, at))
    }

    val f1 = press()
    val f2 = press()
    assert(ready.await(3, TimeUnit.SECONDS))
    start.countDown()

    val r1 = Await.result(f1, 3.seconds)
    val r2 = Await.result(f2, 3.seconds)
    val results = Vector(r1, r2)

    assert(results.exists(_ == Right(Entered(slot))))
    assert(
      results.forall {
        case Right(Entered(`slot`)) => true
        case Right(AwaitingPayment(`slot`, totalYen, paidYen, remainingYen)) =>
          totalYen == yen(0) && paidYen == yen(0) && remainingYen == yen(0)
        case Left(InvalidPaymentOperation(message)) =>
          message == s"slot $slot is already occupied (concurrent enter detected)"
        case _ => false
      }
    )

    val enteredEvents = store.allEvents.collect { case e: CarEntered if e.slot == slot => e }
    assertEquals(enteredEvents.size, 1)
    assertEquals(registry.sessionAt(slot).map(_.isDefined), Right(true))
  }

  test("入庫競合で後着が enter 分岐に入った場合は DomainError を返す") {
    val store = new InMemoryEventStore
    val slot = SlotNo.from(2).getOrElse(fail("valid slot expected"))
    val registry = new SlotRegistry(store) {
      override def sessionAt(s: SlotNo): Either[DomainError, Option[SessionId]] =
        if (s == slot) Right(None) else super.sessionAt(s)
    }
    val app = new ParkingApp(store, registry)

    val first = app.handle(Pressed(slot, Instant.parse("2026-02-19T09:00:00+09:00")))
    val second = app.handle(Pressed(slot, Instant.parse("2026-02-19T09:00:01+09:00")))

    assertEquals(first, Right(Entered(slot)))
    assertEquals(second, Left(InvalidPaymentOperation(s"slot $slot is already occupied (concurrent enter detected)")))
    assertEquals(store.allEvents.count {
      case CarEntered(_, s, _) if s == slot => true
      case _ => false
    }, 1)
  }
}
