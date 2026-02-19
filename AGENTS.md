Parking DDD (Scala 3)

小さなコインパーキングを題材に、DDD + Event Sourcing + FP スタイルの設計を練習する教材プロジェクトです。
永続DBは使わず、ドメインイベントを真実の源泉 (Source of Truth) として扱います。

このファイルは **エージェント作業ルール** を記述します。
プロダクト仕様（料金体系・ドメインルール）の正本は `README.md` です。

ゴール
	•	Bounded Context / レイヤ分離の体験
	•	Aggregate と Policy の分離
	•	「状態 = イベントの再生結果」を体感する
	•	将来、AI エージェントが理解しやすい構造にする

⸻

ディレクトリ構成

src/
  domain/   ドメインモデル（純粋）
  app/      ユースケース（Application Service）
  infra/    技術的実装（EventStore など）
  cli/      入出力（UI）

レイヤ責務
	•	domain: ルール・イベント・Aggregate（副作用なし）
	•	app: コマンド受付・トランザクション境界
	•	infra: 永続化や外部システム（現在はメモリ）
	•	cli: 人間とのインターフェース

依存方向は必ず cli -> app -> domain（infra は app から利用）

⸻

エージェント向け運用ルール
	•	仕様判断が必要なときは `README.md` を優先する
	•	仕様を更新したら、まず `README.md` を更新し、必要なら `AGENTS.md` は参照リンクだけ調整する
	•	イベントは削除/更新せず追記のみ（Event Sourcing）
	•	破損イベント列は fail-fast で扱い、補正は補正イベント追記で扱う
	•	CLI の表示文言にドメイン仕様をハードコードしない（例: `FeePolicy.pricingSummary` を参照）
	•	料金計算は必ず `domain.FeePolicy` を通す
	•	`domain` は純粋に保ち、副作用は `app/infra/cli` に閉じ込める

⸻

実行コマンド（エージェント推奨）
	•	起動: `scala-cli run .`
	•	テスト: `scala-cli test . --server=false`

`--server=false` を付けると、環境によって発生する Bloop/権限エラーを回避しやすくなります。

⸻

設計メモ
	•	Aggregate は「1セッション = 1駐車」
	•	スロット番号は Entity ではなく Value 的に扱う
	•	料金は Policy（ドメインサービス）
	•	Read Model は壊れてもイベントから再構築可能
