package app

import domain._
import infra._
import java.util.UUID

class ParkingApp(store: InMemoryEventStore, registry: SlotRegistry) {

  def handle(cmd: Pressed): Either[DomainError, Unit] = {
    val slot = cmd.slot
    val now = cmd.at

    registry.sessionAt(slot) match {
    case None => // enter
      val sid = UUID.randomUUID().toString
      val e = CarEntered(sid, slot, now)
      store.append(e)
      registry.park(slot, sid)
      println(s"[入庫]  スロット:$slot  時刻:${now}")
      Right(())

    case Some(sid) => // exit
      val events = store.load(sid)
      val enteredAt = events.collect { case CarEntered(_, s, at) if s == slot => at } match {
        case Vector(at) => at
        case Vector() =>
          return Left(CorruptedEventStream(sid, slot, "CarEntered is missing"))
        case _ =>
          return Left(CorruptedEventStream(sid, slot, "Multiple CarEntered found"))
      }
      val session = ParkingSession(sid, slot, enteredAt)
      val (ex, fee) = session.exit(now)
      store.append(ex); store.append(fee)
      registry.leave(slot)

      println(s"[出庫]  スロット:$slot  入:${enteredAt}  出:${now}")
      println(s"         駐車時間:${fee.minutes}分  料金:${fee.feeYen}円")
      Right(())
    }
  }
}
