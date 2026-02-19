package cli

import domain._
import infra._
import app._

import java.time.Instant
import java.util.UUID
import scala.util.Try
import scala.io.StdIn.readLine

@main def runParkingCli(): Unit = {

  val store = new InMemoryEventStore
  val registry = new SlotRegistry(store)
  val app = new ParkingApp(store, registry)

  FeePolicy.pricingSummary match {
    case Left(err) =>
      System.err.println(s"[設定エラー] ${err.message}")
      return
    case Right(summary) =>
      println(s"コインパーキングCLI。料金: ${summary}。1〜9を入力（同じ番号で入出庫）。qで終了。")
  }

  var continue = true
  while (continue) {
    print("> ")
    val line = Option(readLine()).getOrElse("").trim
    line match {
      case "q" | "quit" | "exit" => continue = false
      case s if s.matches("[1-9]") =>
        SlotNo.from(s.toInt).foreach { slot =>
          app.handle(Pressed(slot, Instant.now())) match {
            case Left(err) =>
              System.err.println(s"[整合性エラー] ${err.message}")
            case Right(Entered(_)) =>
              println(s"[入庫] スロット:$slot")
            case Right(AwaitingPayment(_, total, paid, remaining)) =>
              println(s"[出庫要求] スロット:$slot  料金:${total.value}円")
              println(s"支払い金額は ${total.value} 円です。（支払済み: ${paid.value} 円）")
              settlePayment(app, slot, remaining)
            case Right(Exited(_)) =>
              println(s"[出庫] スロット:$slot")
          }
        }
      case _ => System.err.println("1〜9の数字か q を入力してね")
    }
  }
  println("bye")
}

private def settlePayment(app: ParkingApp, slot: SlotNo, initialRemaining: Money): Unit = {
  var remaining = initialRemaining
  if (remaining == Money.Zero) {
    println("支払いは不要です")
    completeExit(app, slot)
    return
  }
  while (remaining > Money.Zero) {
    print(s"投入する金額を入力してください（残り ${remaining.value} 円）> ")
    val line = Option(readLine()).getOrElse("").trim
    if (!line.matches("\\d+")) {
      cancelByInputError(app, slot)
      return
    } else {
      Money.from(Try(line.toInt).getOrElse(-1)) match {
        case None =>
          cancelByInputError(app, slot)
          return
        case Some(amount) =>
          val commandId = UUID.randomUUID().toString
          app.insertMoney(slot, amount, Instant.now(), commandId) match {
            case Left(err) =>
              System.err.println(s"[決済エラー] ${err.message}")
            case Right(PaymentRemaining(_, _, r)) =>
              remaining = r
            case Right(PaymentSettled(_, _, change)) =>
              if (change > Money.Zero) println(s"おつりは ${change.value} 円です")
              completeExit(app, slot)
              remaining = Money.Zero
          }
      }
    }
  }
}

private def cancelByInputError(app: ParkingApp, slot: SlotNo): Unit = {
  app.cancelExitRequest(slot, Instant.now(), "invalid payment input") match {
    case Left(err) =>
      System.err.println(s"[決済エラー] ${err.message}")
    case Right(_) =>
      System.err.println("入金エラー：出庫要求がキャンセルされました")
  }
}

private def completeExit(app: ParkingApp, slot: SlotNo): Unit = {
  print("出庫してください（Enterで確定）")
  readLine()
  val commandId = UUID.randomUUID().toString
  app.finalizeExit(slot, Instant.now(), commandId) match {
    case Left(err) => System.err.println(s"[出庫エラー] ${err.message}")
    case Right(_) =>
      println(s"[出庫] スロット:$slot")
      println(s"${slot} が空きました")
  }
}
