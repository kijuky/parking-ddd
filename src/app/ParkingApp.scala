package app

import domain._
import infra._

sealed trait PressedResult
case class Entered(slot: SlotNo) extends PressedResult
case class AwaitingPayment(slot: SlotNo, totalYen: Money, paidYen: Money, remainingYen: Money) extends PressedResult

sealed trait PaymentResult
case class PaymentRemaining(totalYen: Money, paidYen: Money, remainingYen: Money) extends PaymentResult
case class PaymentSettled(totalYen: Money, paidYen: Money, changeYen: Money) extends PaymentResult

case class Exited(slot: SlotNo) extends PressedResult

class ParkingApp(store: InMemoryEventStore, registry: SlotRegistry) {
  private val paymentFlow = new PaymentFlow(store, registry)

  def handle(cmd: Pressed): Either[DomainError, PressedResult] = {
    val slot = cmd.slot
    val now = cmd.at

    registry.sessionAt(slot) match {
      case Left(err) =>
        Left(err)
      case Right(None) => // enter
        store.appendEnterFirstWins(slot, now).flatMap {
          case EnteredAppended(_) =>
            Right(Entered(slot))
          case EnterRejectedOccupied(_) =>
            Left(InvalidPaymentOperation(s"slot $slot is already occupied (concurrent enter detected)"))
        }
      case Right(Some(sid)) => // exit
        val (events, version) = store.loadWithVersion(sid)
        val enteredAt = events.collect { case CarEntered(_, s, at) if s == slot => at } match {
          case Vector(at) => at
          case Vector() =>
            return Left(CorruptedEventStream(sid, slot, "CarEntered is missing"))
          case _ =>
            return Left(CorruptedEventStream(sid, slot, "Multiple CarEntered found"))
        }
        paymentFlow.phase(events, slot) match {
          case EnteredPhase =>
            val session = ParkingSession(sid, slot, enteredAt)
            session.quote(now).flatMap { fee =>
              val request = ExitRequested(sid, slot, fee.feeYen, now)
              val maybeCompleted =
                if (fee.feeYen == Money.Zero) Vector(PaymentCompleted(sid, slot, Money.Zero, Money.Zero, Money.Zero, now))
                else Vector.empty
              val newEvents = Vector(fee, request) ++ maybeCompleted
              store.compareAndAppend(sid, version, newEvents) match {
                case Left(_) =>
                  Left(InvalidPaymentOperation("concurrent update detected"))
                case Right(_) =>
                  Right(AwaitingPayment(slot, fee.feeYen, Money.Zero, fee.feeYen))
              }
            }
          case ExitRequestedPhase(totalYen) =>
            Right(AwaitingPayment(slot, totalYen, Money.Zero, totalYen))
          case PayingPhase(totalYen, paidYen) =>
            totalYen.safeMinus(paidYen) match {
              case Right(remainingYen) =>
                Right(AwaitingPayment(slot, totalYen, paidYen, remainingYen))
              case Left(err) =>
                Left(CorruptedEventStream(sid, slot, s"invalid payment balance: ${err.message}"))
            }
          case PaidPhase(totalYen, paidYen, _) =>
            Right(AwaitingPayment(slot, totalYen, paidYen, Money.Zero))
          case ExitedPhase =>
            Left(InvalidPaymentOperation(s"slot $slot is not occupied"))
        }
    }
  }

  def insertMoney(
    slot: SlotNo,
    amountYen: Money,
    at: java.time.Instant,
    commandId: String
  ): Either[DomainError, PaymentResult] =
    paymentFlow.insertMoney(slot, amountYen, at, commandId)

  def finalizeExit(slot: SlotNo, at: java.time.Instant, commandId: String): Either[DomainError, Exited] =
    paymentFlow.finalizeExit(slot, at, commandId)

  def cancelExitRequest(slot: SlotNo, at: java.time.Instant, reason: String): Either[DomainError, Unit] =
    paymentFlow.cancelExitRequest(slot, at, reason)
}
