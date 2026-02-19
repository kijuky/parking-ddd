# 用語集 (Glossary)

このドキュメントは、このプロジェクトで使うドメイン用語の定義を固定するためのものです。
仕様の正本は [README.md](../README.md) ですが、解釈がぶれやすい語はここを優先して参照します。

## 同一料金帯の連続区間
同じ料金帯（例: 平日夜）に属する時間が、切れ目なく連続している区間。
最大料金（cap）は、この連続区間に対して適用する。
0:00 を跨ぐと平日/休日が切り替わるため、同じ「夜」でも別区間になる場合がある。
実装参照: [`FeePolicy.calcFeeYen`](../src/domain/ParkingDomain.scala), [`FeePolicy.nextBoundary`](../src/domain/ParkingDomain.scala)

## 料金帯
`TimeBand` で表現される時間帯ルール。現在は `day` と `night` を使う。
各料金帯は `Rate` を持つ。
実装参照: [`TimeBand`](../src/domain/ParkingDomain.scala), [`Rate`](../src/domain/ParkingDomain.scala)

## 料金カレンダー
`PricingCalendar` が、曜日種別（平日/休日）ごとの料金帯集合を持つ。
`FeePolicy` はこのカレンダーを参照して料金を合成計算する。
実装参照: [`PricingCalendar`](../src/domain/ParkingDomain.scala), [`FeePolicy.calendar`](../src/domain/ParkingDomain.scala)

## 上限料金 (Cap)
`CapRule` で表現される料金上限。
- `Uncapped`: 上限なし
- `Capped(maxYen)`: 上限あり
実装参照: [`CapRule`](../src/domain/ParkingDomain.scala), [`Rate.applyCap`](../src/domain/ParkingDomain.scala)

## 平日 / 休日
このプロジェクトでは、休日は土日（`SATURDAY`, `SUNDAY`）のみを指す。
祝日は考慮しない。
実装参照: [`PricingCalendar.bandsAt`](../src/domain/ParkingDomain.scala)

## ドメインエラー
業務上想定される失敗を `DomainError` で表現する。
現在は `CorruptedEventStream`, `InvalidPricingCalendar`, `MissingTimeBand` を使用し、`Either[DomainError, ...]` で返す。
実装参照: [`DomainError`](../src/domain/ParkingDomain.scala), [`ParkingApp.handle`](../src/app/ParkingApp.scala)

## 破損イベント列
イベント履歴がドメイン上の前提を満たさない状態。
現在の出庫処理では、`CarEntered` の欠落または重複を破損として扱う。
実装参照: [`ParkingApp.handle`](../src/app/ParkingApp.scala), [`CorruptedEventStream`](../src/domain/ParkingDomain.scala)

## 補正イベント
既存イベントを削除/更新せず、整合性を補正するために追記するイベント。
現在は `DataRepairRequested` と `SessionReconciled` を用いる。
設計参照: [補正イベント最小設計](./repair-events-minimal-design.md)
実装参照: [`DataRepairRequested`](../src/domain/ParkingDomain.scala), [`SessionReconciled`](../src/domain/ParkingDomain.scala), [`RepairApp`](../src/app/RepairApp.scala)
