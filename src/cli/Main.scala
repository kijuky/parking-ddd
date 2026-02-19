package cli

import domain._
import infra._
import app._

import java.time.Instant
import scala.io.StdIn.readLine

@main def runParkingCli(): Unit = {

  val store = new InMemoryEventStore
  val registry = new SlotRegistry
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
            case Left(err) => System.err.println(s"[整合性エラー] ${err.message}")
            case Right(_) => ()
          }
        }
      case _ => System.err.println("1〜9の数字か q を入力してね")
    }
  }
  println("bye")
}
