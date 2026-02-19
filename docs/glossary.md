# 用語集 (Glossary)

このドキュメントは、このプロジェクトで使うドメイン用語の定義を固定するためのものです。  
仕様の正本は [README.md](../README.md) ですが、解釈がぶれやすい語はここを優先して参照します。

## 1. 料金に関する用語

### 料金帯
`TimeBand` で表現される時間帯ルール。現在は `day` と `night` を使う。  
各料金帯は `Rate` を持つ。  
実装参照: [`TimeBand`](../src/domain/ParkingDomain.scala), [`Rate`](../src/domain/ParkingDomain.scala)

### 料金カレンダー
`PricingCalendar` が、曜日種別（平日/休日）ごとの料金帯集合を持つ。  
`FeePolicy` はこのカレンダーを参照して料金を合成計算する。  
実装参照: [`PricingCalendar`](../src/domain/ParkingDomain.scala), [`FeePolicy.calendar`](../src/domain/ParkingDomain.scala)

### 平日 / 休日
このプロジェクトでは、休日は土日（`SATURDAY`, `SUNDAY`）のみを指す。  
祝日は考慮しない。  
実装参照: [`PricingCalendar.bandsAt`](../src/domain/ParkingDomain.scala)

### 同一料金帯の連続区間
同じ料金帯（例: 平日夜）に属する時間が、切れ目なく連続している区間。  
最大料金（cap）は、この連続区間に対して適用する。  
0:00 を跨ぐと平日/休日が切り替わるため、同じ「夜」でも別区間になる場合がある。  
実装参照: [`FeePolicy.calcFeeYen`](../src/domain/ParkingDomain.scala), [`FeePolicy.nextBoundary`](../src/domain/ParkingDomain.scala)

### 上限料金 (Cap)
`CapRule` で表現される料金上限。  
- `Uncapped`: 上限なし
- `Capped(maxYen)`: 上限あり  
実装参照: [`CapRule`](../src/domain/ParkingDomain.scala), [`Rate.applyCap`](../src/domain/ParkingDomain.scala)

### 金額 (Money)
非負の金額を表す値オブジェクト。料金計算・決済関連イベントでは `Int` ではなく `Money` を使う。  
実装参照: [`Money`](../src/domain/ParkingDomain.scala), [`FeeCalculated`](../src/domain/ParkingDomain.scala), [`MoneyInserted`](../src/domain/ParkingDomain.scala)

## 2. 駐車と決済に関する用語

### 出庫要求
駐車セッションを即時に終了せず、支払い待ちに遷移させる操作。  
`ExitRequested` イベントで料金を確定し、以降は入金イベントを積み上げる。  
実装参照: [`ExitRequested`](../src/domain/ParkingDomain.scala), [`ParkingApp.handle`](../src/app/ParkingApp.scala)

### 出庫要求キャンセル
出庫要求後に決済を取りやめ、入庫状態へ戻す操作。  
`ExitRequestCancelled` を追記し、次の押下で再度 `ExitRequested` を作れる状態に戻る。  
実装参照: [`ExitRequestCancelled`](../src/domain/ParkingDomain.scala), [`PaymentFlow.cancelExitRequest`](../src/app/PaymentFlow.scala)

### 分割入金
料金に達するまで複数回に分けて入金すること。  
`MoneyInserted` を追記し、合計入金額が料金以上になったら `PaymentCompleted` を発行する。  
実装参照: [`MoneyInserted`](../src/domain/ParkingDomain.scala), [`PaymentCompleted`](../src/domain/ParkingDomain.scala), [`PaymentFlow.insertMoney`](../src/app/PaymentFlow.scala)

### 決済フロー
出庫要求以降の「支払い待ち状態の復元」「入金」「出庫確定」を扱う app 層のフロー。  
イベント列から状態を再生し、`PaymentResult` / `Exited` を返す。  
実装参照: [`PaymentFlow`](../src/app/PaymentFlow.scala), [`PaymentPhase`](../src/app/PaymentFlow.scala), [`PaymentResult`](../src/app/ParkingApp.scala)

## 3. 同時実行・整合性に関する用語

### コマンドID (commandId)
同一リクエストの再送判定に使う識別子。時刻とは別概念で、通常は UUID などを使う。  
`insertMoney` / `finalizeExit` は同一 `commandId` の再送時に冪等（同一結果返却）として扱う。  
`insertMoney` は同一 `commandId` でも金額が異なる再送を `InvalidPaymentOperation` として拒否する。  
実装参照: [`PaymentFlow.insertMoney`](../src/app/PaymentFlow.scala), [`PaymentFlow.finalizeExit`](../src/app/PaymentFlow.scala), [`MoneyInserted`](../src/domain/ParkingDomain.scala), [`CarExited`](../src/domain/ParkingDomain.scala)

### 同時入庫競合（先勝ち）
同一スロットに対する入庫操作が競合した場合、先に確定した入庫のみを有効とし、後着は拒否する。  
後着は `InvalidPaymentOperation("slot X is already occupied (concurrent enter detected)")` を返す。  
実装参照: [`InMemoryEventStore.appendEnterFirstWins`](../src/infra/InMemoryEventStore.scala), [`ParkingApp.handle`](../src/app/ParkingApp.scala)

### 楽観ロック (Optimistic Lock)
同一セッションへの同時更新を、イベント列バージョンの一致確認で検出する方式。  
期待バージョンと実バージョンが不一致なら更新を拒否し、競合として扱う。  
実装参照: [`InMemoryEventStore.compareAndAppend`](../src/infra/InMemoryEventStore.scala), [`ParkingApp.handle`](../src/app/ParkingApp.scala), [`PaymentFlow.insertMoney`](../src/app/PaymentFlow.scala)

## 4. エラーと補正に関する用語

### ドメインエラー
業務上想定される失敗を `DomainError` で表現する。  
現在は `CorruptedEventStream`, `InvalidPricingCalendar`, `MissingTimeBand`, `InvalidPaymentOperation`, `InvalidRepairRequest`, `InvalidMoney` を使用し、`Either[DomainError, ...]` で返す。  
実装参照: [`DomainError`](../src/domain/ParkingDomain.scala), [`ParkingApp.handle`](../src/app/ParkingApp.scala)

### 破損イベント列
イベント履歴がドメイン上の前提を満たさない状態。  
現在の出庫処理では、`CarEntered` の欠落または重複を破損として扱う。  
実装参照: [`ParkingApp.handle`](../src/app/ParkingApp.scala), [`CorruptedEventStream`](../src/domain/ParkingDomain.scala)

### 補正イベント
既存イベントを削除/更新せず、整合性を補正するために追記するイベント。  
現在は `DataRepairRequested` と `SessionReconciled` を用いる。  
設計参照: [補正イベント最小設計](./repair-events-minimal-design.md)  
実装参照: [`DataRepairRequested`](../src/domain/ParkingDomain.scala), [`SessionReconciled`](../src/domain/ParkingDomain.scala), [`RepairApp`](../src/app/RepairApp.scala)

## 5. 将来拡張用語

### 監査/照合 Read Model（未実装）
`FeeCalculated`（請求）と `MoneyInserted` / `PaymentCompleted`（実入金・釣り銭）を集計し、未収/過収を可視化するための投影。  
現在の実装範囲（CLI学習用）では未実装で、将来拡張ポイントとして扱う。
