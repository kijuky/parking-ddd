package app

import domain._
import infra._

class RepairApp(store: InMemoryEventStore) {

  def handle(cmd: RequestRepair): Either[DomainError, DataRepairRequested] = {
    if (cmd.reason.trim.isEmpty) return Left(InvalidRepairRequest("reason must not be empty"))
    val event = DataRepairRequested(cmd.sessionId, cmd.slot, cmd.reason, cmd.at)
    store.append(event)
    Right(event)
  }

  def handle(cmd: ReconcileSession): Either[DomainError, SessionReconciled] = {
    RepairPolicy.reconcile(
      sessionId = cmd.sessionId,
      slot = cmd.slot,
      correctedEnteredAt = cmd.correctedEnteredAt,
      correctedExitedAt = cmd.correctedExitedAt,
      note = cmd.note,
      at = cmd.at
    ).map { event =>
      store.append(event)
      event
    }
  }
}
