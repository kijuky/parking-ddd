Parking DDD (Scala 3)

小さなコインパーキングを題材に、DDD + Event Sourcing + FP スタイルの設計を練習する教材プロジェクトです。
永続DBは使わず、ドメインイベントを真実の源泉 (Source of Truth) として扱います。

このREADMEは、プロジェクト仕様と実装状況の正本ドキュメントです。

ゴール
	•	ドメイン知識を壊さずに機能追加する
	•	レイヤ境界を越えるショートカットを禁止する
	•	生成コードのレビュー負荷を下げる

⸻

絶対ルール（Must）
	1.	domain は純粋関数的に保つ
	•	println, Instant.now, Thread.sleep, I/O を禁止
	2.	domain は infra を import しない
	3.	app は状態を保持しない（永続は EventStore 経由）
	4.	イベントを削除・更新しない（追記のみ）
	5.	料金計算は必ず FeePolicy を通す

⸻

許可される変更
	•	新しいドメインイベントの追加
	•	新しいコマンドの追加
	•	Read Model の追加（投影）
	•	EventStore 実装の追加（例: File, SQLite）

禁止事項（Do Not）
	•	domain に DB 依存を入れる
	•	UI から直接 EventStore を触る
	•	Aggregate 外で料金を計算する
	•	既存イベントのフィールド意味を変更する（破壊的変更）

⸻

機能追加の手順（推奨フロー）
	1.	追加したい振る舞いを「イベント」で表現する
	2.	Aggregate にコマンド処理を実装
	3.	Application Service から呼び出す
	4.	必要なら Read Model を追加
	5.	CLI/UI を更新

⸻

命名規約
	•	コマンド: 動詞の過去分詞を避け、操作を表す（例: Pressed, PayRequested）
	•	イベント: 完了形（例: CarEntered, FeeCalculated）
	•	Policy: XxxPolicy
	•	Read Model: XxxView or XxxRegistry

⸻

テスト指針
	•	Given(過去イベント) / When(コマンド) / Then(新規イベント) で書く
	•	期待値は「状態」ではなく「発行イベント」を検証する

⸻

エージェントへのヒント
	•	迷ったら domain を最小に、infra を後回しにする
	•	仕様が曖昧な場合はイベント名から先に提案する
	•	副作用は app/infra に閉じ込める

⸻

<a id="pricing-policy-current"></a>
現在の実装状況（2026-02 時点）
	•	料金ポリシー:
	•	最初の5分は無料
	•	平日（月〜金）:
	•	昼(9:00-18:00): 30分ごと200円（切り上げ、上限なし）
	•	夜(18:00-翌9:00): 60分ごと200円（切り上げ、最大1800円）
	•	休日（土日）:
	•	昼(9:00-18:00): 60分ごと200円（切り上げ、最大1800円）
	•	夜(18:00-翌9:00): 60分ごと100円（切り上げ、最大900円）
	•	昼夜や日付（0:00）をまたぐ場合は、時間帯ごとに分割して料金を合算
	•	最大料金は「同一料金帯の連続区間」に適用
	•	料金計算は `domain.FeePolicy` に集約
	•	CLI の料金表示は `FeePolicy.pricingSummary` を参照（文言ハードコードを排除）
	•	スロット番号:
	•	`SlotNo` は値オブジェクト化済み（`SlotNo.from` で 1..9 を検証）
	•	破損イベント列の検知:
	•	出庫時に `CarEntered` が欠落/重複していた場合は `CorruptedEventStream` を送出
	•	補正イベント（最小設計）:
	•	コマンド: `RequestRepair`, `ReconcileSession`
	•	イベント: `DataRepairRequested`, `SessionReconciled`
	•	`app.RepairApp` でイベント追記まで実装済み

⸻

テスト実行
	•	`scala-cli test . --server=false`
	•	`test/domain`: FeePolicy, SlotNo, ParkingSession
	•	`test/app`: ParkingApp, RepairApp
