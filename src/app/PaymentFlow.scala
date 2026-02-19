package app

import domain._
import infra._
import java.time.Instant

sealed trait PaymentPhase
case object EnteredPhase extends PaymentPhase
case class ExitRequestedPhase(totalYen: Money) extends PaymentPhase
case class PayingPhase(totalYen: Money, paidYen: Money) extends PaymentPhase {
  def remainingYen: Either[DomainError, Money] = totalYen.safeMinus(paidYen)
}
case class PaidPhase(totalYen: Money, paidYen: Money, changeYen: Money) extends PaymentPhase
case object ExitedPhase extends PaymentPhase

class PaymentFlow(store: InMemoryEventStore, registry: SlotRegistry) {

  def phase(events: Vector[Event], slot: SlotNo): PaymentPhase = {
    events.foldLeft(EnteredPhase: PaymentPhase) {
      case (state, ExitRequested(_, s, totalYen, _)) if s == slot =>
        ExitRequestedPhase(totalYen)
      case (state, ExitRequestCancelled(_, s, _, _)) if s == slot =>
        EnteredPhase
      case (ExitRequestedPhase(totalYen), MoneyInserted(_, s, _, _, paidYenAfter, _, _, _)) if s == slot =>
        PayingPhase(totalYen, paidYenAfter)
      case (PayingPhase(totalYen, _), MoneyInserted(_, s, _, _, paidYenAfter, _, _, _)) if s == slot =>
        PayingPhase(totalYen, paidYenAfter)
      case (_, PaymentCompleted(_, s, totalYen, paidYen, changeYen, _, _)) if s == slot =>
        PaidPhase(totalYen, paidYen, changeYen)
      case (_, CarExited(_, s, _, _)) if s == slot =>
        ExitedPhase
      case (state, _) =>
        state
    }
  }

  def insertMoney(slot: SlotNo, amountYen: Money, at: Instant, commandId: String): Either[DomainError, PaymentResult] = {
    if (amountYen == Money.Zero) return Left(InvalidPaymentOperation("inserted amount must be positive"))
    activeSession(slot).flatMap { sessionId =>
      findInsertByCommandId(sessionId, commandId, slot) match {
        case Some((existingAmount, result)) =>
          if (existingAmount != amountYen)
            Left(InvalidPaymentOperation("commandId was already used with different amount"))
          else Right(result)
        case None =>
          val (events, version) = store.loadWithVersion(sessionId)
          phase(events, slot) match {
            case EnteredPhase =>
              Left(InvalidPaymentOperation("exit was not requested"))
            case ExitRequestedPhase(totalYen) =>
              appendMoney(sessionId, slot, amountYen, at, commandId, totalYen, Money.Zero, version)
            case PayingPhase(totalYen, paidYen) =>
              appendMoney(sessionId, slot, amountYen, at, commandId, totalYen, paidYen, version)
            case PaidPhase(_, _, _) =>
              Left(InvalidPaymentOperation("payment is already settled"))
            case ExitedPhase =>
              Left(InvalidPaymentOperation("session is already exited"))
          }
      }
    }
  }

  def finalizeExit(slot: SlotNo, at: Instant, commandId: String): Either[DomainError, Exited] = {
    registry.sessionAt(slot) match {
      case Right(Some(sessionId)) =>
        findExitByCommandId(sessionId, commandId, slot) match {
          case Some(existingSlot) if existingSlot == slot =>
            Right(Exited(slot))
          case Some(_) =>
            Left(InvalidPaymentOperation("commandId was already used for another slot"))
          case None =>
            val (events, version) = store.loadWithVersion(sessionId)
            phase(events, slot) match {
              case EnteredPhase =>
                Left(InvalidPaymentOperation("exit was not requested"))
              case ExitRequestedPhase(_) | PayingPhase(_, _) =>
                Left(InvalidPaymentOperation("payment is not completed"))
              case PaidPhase(_, _, _) =>
                val exited = CarExited(sessionId, slot, at, Some(commandId))
                store.compareAndAppend(sessionId, version, Vector(exited)) match {
                  case Left(_) =>
                    Left(InvalidPaymentOperation("concurrent update detected"))
                  case Right(_) =>
                    Right(Exited(slot))
                }
              case ExitedPhase =>
                Left(InvalidPaymentOperation("session is already exited"))
            }
        }
      case Right(None) =>
        Left(InvalidPaymentOperation(s"slot $slot is not occupied"))
      case Left(err) =>
        Left(err)
    }
  }

  private def findExitByCommandId(sessionId: SessionId, commandId: String, slot: SlotNo): Option[SlotNo] =
    store.load(sessionId).collectFirst {
      case CarExited(_, s, _, Some(id)) if id == commandId => s
    }

  def cancelExitRequest(slot: SlotNo, at: Instant, reason: String): Either[DomainError, Unit] = {
    if (reason.trim.isEmpty) return Left(InvalidPaymentOperation("cancel reason must not be empty"))
    activeSession(slot).flatMap { sessionId =>
      val (events, version) = store.loadWithVersion(sessionId)
      phase(events, slot) match {
        case ExitRequestedPhase(_) | PayingPhase(_, _) =>
          val cancelled = ExitRequestCancelled(sessionId, slot, reason, at)
          store.compareAndAppend(sessionId, version, Vector(cancelled)) match {
            case Left(_) => Left(InvalidPaymentOperation("concurrent update detected"))
            case Right(_) => Right(())
          }
        case EnteredPhase =>
          Left(InvalidPaymentOperation("exit was not requested"))
        case PaidPhase(_, _, _) =>
          Left(InvalidPaymentOperation("payment is already completed"))
        case ExitedPhase =>
          Left(InvalidPaymentOperation("session is already exited"))
      }
    }
  }

  private def appendMoney(
    sessionId: SessionId,
    slot: SlotNo,
    amountYen: Money,
    at: Instant,
    commandId: String,
    totalYen: Money,
    currentPaidYen: Money,
    expectedVersion: Long
  ): Either[DomainError, PaymentResult] = {
    val paidYen = currentPaidYen + amountYen
    val remainingYen = if (paidYen >= totalYen) Money.Zero else totalYen - paidYen
    val inserted = MoneyInserted(
      sessionId,
      slot,
      amountYen,
      totalYen,
      paidYen,
      remainingYen,
      at,
      Some(commandId)
    )
    if (paidYen >= totalYen) {
      val changeYen = paidYen - totalYen
      val completed = PaymentCompleted(sessionId, slot, totalYen, paidYen, changeYen, at, Some(commandId))
      store.compareAndAppend(sessionId, expectedVersion, Vector(inserted, completed)) match {
        case Left(_) => Left(InvalidPaymentOperation("concurrent update detected"))
        case Right(_) => Right(PaymentSettled(totalYen, paidYen, changeYen))
      }
    } else {
      store.compareAndAppend(sessionId, expectedVersion, Vector(inserted)) match {
        case Left(_) => Left(InvalidPaymentOperation("concurrent update detected"))
        case Right(_) => Right(PaymentRemaining(totalYen, paidYen, remainingYen))
      }
    }
  }

  private def activeSession(slot: SlotNo): Either[DomainError, SessionId] =
    registry.sessionAt(slot).flatMap {
      case Some(sessionId) => Right(sessionId)
      case None => Left(InvalidPaymentOperation(s"slot $slot is not occupied"))
    }

  private def findInsertByCommandId(
    sessionId: SessionId,
    commandId: String,
    slot: SlotNo
  ): Option[(Money, PaymentResult)] = {
    val byCommand = store.load(sessionId).filter {
      case MoneyInserted(_, _, _, _, _, _, _, Some(id)) if id == commandId => true
      case PaymentCompleted(_, _, _, _, _, _, Some(id)) if id == commandId => true
      case _ => false
    }

    val settled = byCommand.collectFirst {
      case PaymentCompleted(_, s, totalYen, paidYen, changeYen, _, _) if s == slot =>
        (totalYen, paidYen, changeYen)
    }
    settled
      .flatMap { case (totalYen, paidYen, changeYen) =>
        byCommand.collectFirst {
          case MoneyInserted(_, s, amountYen, _, _, _, _, _) if s == slot =>
            (amountYen, PaymentSettled(totalYen, paidYen, changeYen): PaymentResult)
        }
      }
      .orElse(
        byCommand.collectFirst {
          case MoneyInserted(_, s, amountYen, totalYen, paidYenAfter, remainingYenAfter, _, _) if s == slot =>
            (amountYen, PaymentRemaining(totalYen, paidYenAfter, remainingYenAfter): PaymentResult)
        }
      )
  }

}
