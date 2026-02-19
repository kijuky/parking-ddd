Parking DDD (Scala 3)

小さなコインパーキングを題材に、DDD + Event Sourcing + FP スタイルの設計を練習するための教材プロジェクトです。永続DBは使わず、ドメインイベントを真実の源泉 (Source of Truth) として扱います。

ゴール
	•	Bounded Context / レイヤ分離の体験
	•	Aggregate と Policy の分離
	•	「状態 = イベントの再生結果」を体感する
	•	将来、AI エージェントが理解しやすい構造にする

⸻

実行方法

前提: scala-cli をインストール済み

scala-cli run .

起動後、1〜9 の数字で操作できます（同じ番号の再押下で出庫）。q で終了。

⸻

料金ポリシー
	•	30分ごと 200円
	•	切り上げ課金

例: 1分 → 200円 / 31分 → 400円

domain.FeePolicy に集約されています（UI/アプリ層から計算ロジックを分離）。

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

ドメインイベント
	•	CarEntered
	•	CarExited
	•	FeeCalculated

イベントは「事実の記録」であり、取り消しません。状態表示は Read Model（SlotRegistry）が担当します。

⸻

今後の拡張案
	•	EventStore をファイル永続化（再起動復元）
	•	料金テーブルの外部設定化
	•	時間帯料金 / 上限料金の導入
	•	Web UI 追加（CLI は残す）
	•	テスト: イベント列を与えて期待イベントを検証する

⸻

設計メモ
	•	Aggregate は「1セッション = 1駐車」
	•	スロット番号は Entity ではなく Value 的に扱う
	•	料金は Policy（ドメインサービス）
	•	Read Model は壊れてもイベントから再構築可能
