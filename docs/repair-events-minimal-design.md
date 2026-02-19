# 補正イベント最小設計

関連参照:
- [README.md](../README.md)
- [用語集](./glossary.md)
- 実装: [`src/domain/ParkingDomain.scala`](../src/domain/ParkingDomain.scala)
- 実装: [`src/app/RepairApp.scala`](../src/app/RepairApp.scala)

## 1. 目的
- 破損イベント列を検知したとき、既存イベントを削除せずに補正可能にする。
- 監査可能性を維持するため、補正操作自体をイベントとして残す。

## 2. 現状実装（最小）

### 2.1 追加コマンド
- `RequestRepair(sessionId, slot, reason, at)`
  - 運用者が「このセッションは整合性確認が必要」と記録する操作。
- `ReconcileSession(sessionId, slot, correctedEnteredAt, correctedExitedAt, note, at)`
  - 運用者が補正結果を確定する操作。

### 2.2 追加イベント
- `DataRepairRequested(sessionId, slot, reason, at)`
  - 補正要求が上がった事実。
- `SessionReconciled(sessionId, slot, correctedEnteredAt, correctedExitedAt, correctedMinutes, correctedFeeYen, note, at)`
  - 補正後の確定値（時間・料金）を保持する事実。

### 2.3 ドメインポリシー
- `RepairPolicy.reconcile(...)`
  - 補正後の入出庫時刻から `minutes` / `feeYen` を算出し、`Either[DomainError, SessionReconciled]` を返す。
  - 料金計算は既存の `FeePolicy` を必ず通す。

### 2.4 運用フロー（実装済み範囲）
1. 出庫処理でイベント列破損を検知したら `Left(CorruptedEventStream)` を返す（`Either[DomainError, ...]`）。
2. 運用者が `RequestRepair` を実行し、`DataRepairRequested` を追記する。
3. 調査後に `ReconcileSession` を実行し、`SessionReconciled` を追記する。

## 3. 将来拡張（未実装）
1. 参照系で「最新の `SessionReconciled` を優先」して表示・集計する。
2. `SessionReconciled` を反映する専用 Read Model を追加する。
3. 補正ワークフローの権限管理を導入する。

## 4. 非目標（この段階では扱わない）
- 既存イベントの削除・更新
- 補正処理の自動化（運用者判断を前提）
