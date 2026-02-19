# Parking DDD (Scala 3)

小さなコインパーキングを題材に、DDD + Event Sourcing + FP スタイルを学ぶ教材プロジェクトです。  
永続DBは使わず、ドメインイベントを真実の源泉 (Source of Truth) として扱います。

このREADMEは仕様と実装状況の正本です。  
用語定義は [用語集](./docs/glossary.md) を参照してください。

## 1. このプロジェクトが扱う世界

### 1.1 一般的なコインパーキング
- 車は「入庫 → 出庫要求 → 支払い → 出庫」の流れで利用する。
- 料金は時間単位で発生し、切り上げ課金や最大料金がある。
- 実運用では誤検知・二重入力・同時操作などの例外系が起こりうる。

### 1.2 この教材での目的
- ドメイン知識を壊さずに機能追加する。
- レイヤ境界を越えるショートカットを避ける。
- イベントソーシングで「状態 = イベント再生結果」を体感する。

## 2. 利用者から見た基本機能

### 2.1 CLI操作
- 起動後に `1..9` を入力してスロットを操作する。
- 同じ番号の再入力で出庫要求に進む。
- `q` で終了する。

### 2.2 決済の基本挙動
- 出庫要求で料金を確定する。
- 入金は分割可能。
- 料金到達で決済完了となり、出庫確定できる。
- 不正な入金入力は出庫要求キャンセルとして扱い、入庫状態に戻す。

## 3. 料金仕様

<a id="pricing-policy-current"></a>
### 3.1 現在の料金ポリシー（2026-02時点）
- 最初の5分は無料。
- 平日（月〜金）
  - 昼 `9:00-18:00`: 30分ごと200円（切り上げ、上限なし）
  - 夜 `18:00-翌9:00`: 60分ごと200円（切り上げ、最大1800円）
- 休日（土日）
  - 昼 `9:00-18:00`: 60分ごと200円（切り上げ、最大1800円）
  - 夜 `18:00-翌9:00`: 60分ごと100円（切り上げ、最大900円）

### 3.2 料金計算の細則
- 昼夜や日付（`0:00`）を跨ぐ場合は、時間帯ごとに分割して合算する。
- 最大料金は「同一料金帯の連続区間」に適用する。
- 料金計算は [`domain.FeePolicy`](./src/domain/ParkingDomain.scala) のみで行う。
- CLIの料金表示は [`FeePolicy.pricingSummary`](./src/domain/ParkingDomain.scala) を参照する。

## 4. アーキテクチャ

### 4.1 ディレクトリ
```txt
src/
  domain/   ドメインモデル（純粋）
  app/      ユースケース（Application Service）
  infra/    技術的実装（EventStore など）
  cli/      入出力（UI）
```

### 4.2 レイヤ責務
- `domain`: ルール・イベント・Aggregate（副作用なし）
- `app`: コマンド受付・トランザクション境界
- `infra`: 永続化や外部システム（現状はメモリ）
- `cli`: 人間とのインターフェース

### 4.3 依存方向
- `cli -> app -> domain`
- `infra` は `app` から利用する。

## 5. ドメインモデル（このプロジェクト固有）

### 5.1 主な値オブジェクト
- `SlotNo`: 1..9 のみ有効。
- `Money`: 非負制約を持つ金額。

制約:
- `Money` は学習用に `Int` 範囲（約21億円）前提で運用する。

### 5.2 主なイベント
- 入出庫: `CarEntered`, `CarExited`
- 料金/決済: `FeeCalculated`, `ExitRequested`, `ExitRequestCancelled`, `MoneyInserted`, `PaymentCompleted`
- 補正: `DataRepairRequested`, `SessionReconciled`

### 5.3 決済フローの責務分割
- [`app.ParkingApp`](./src/app/ParkingApp.scala): ユースケースの起点制御
- [`app.PaymentFlow`](./src/app/PaymentFlow.scala): 支払い待ち状態の復元、入金、出庫確定

## 6. 同時実行性・整合性

### 6.1 冪等性
- `insertMoney` / `finalizeExit` は `commandId` を受け取り、同一 `commandId` の再送時に同一結果を返す。
- `commandId` は時刻ではなくリクエスト識別子（UUID等）。

制約:
- `insertMoney` は同一 `commandId` でも金額不一致の再送を `InvalidPaymentOperation` として拒否する。

### 6.2 競合制御
- `InMemoryEventStore.compareAndAppend`（CAS）で楽観ロックを行う。
- 競合時は `InvalidPaymentOperation("concurrent update detected")` を返す。

### 6.3 同時入庫の扱い
- 同時入庫は先勝ち（first-wins）。
- 後着は `InvalidPaymentOperation("slot X is already occupied (concurrent enter detected)")` を返す。

## 7. エラーモデル

### 7.1 DomainError
`Either[DomainError, ...]` で業務エラーを返す。

主な型:
- `CorruptedEventStream`
- `InvalidPricingCalendar`
- `MissingTimeBand`
- `InvalidPaymentOperation`
- `InvalidRepairRequest`
- `InvalidMoney`

### 7.2 破損イベント列と補正
- `CarEntered` 欠落/重複などは `CorruptedEventStream` として検知する。
- 既存イベントは削除/更新せず、補正イベントを追記する。
- 補正設計は [docs/repair-events-minimal-design.md](./docs/repair-events-minimal-design.md) を参照。

## 8. 性能と運用上の扱い

### 8.1 意図的に簡略化している点（学習用途）
- `SlotRegistry` は EventStore から都度再生（全イベント走査）する。
- 性能最適化用の専用Read Modelは未導入（必要時に導入する前提）。
- CLIは単一プロセス/単一スレッドのデモ用途で、CLIレベルのE2E競合試験は対象外。

## 9. 変更時の基本ルール

### 9.1 Must
1. `domain` は純粋関数的に保つ（`println`/`Instant.now`/I/O禁止）。
2. `domain` は `infra` を import しない。
3. `app` は状態を保持しない（永続は EventStore 経由）。
4. イベントは削除・更新せず追記のみ。
5. 料金計算は必ず `FeePolicy` を通す。

### 9.2 Do Not
- `domain` にDB依存を入れない。
- UIから直接EventStoreを触らない。
- Aggregate外で料金を計算しない。
- 既存イベントの意味を破壊的変更しない。

## 10. 実行とテスト

### 10.1 実行
- `scala-cli run .`

### 10.2 テスト
- `scala-cli test . --server=false`

対象:
- `test/domain`: `FeePolicy`, `SlotNo`, `ParkingSession`, `Money`, `PricingCalendar`
- `test/app`: `ParkingApp`, `RepairApp`
