Parking DDD (Scala 3)

小さなコインパーキングを題材に、DDD + Event Sourcing + FP スタイルの設計を練習する教材プロジェクトです。
永続DBは使わず、ドメインイベントを真実の源泉 (Source of Truth) として扱います。

このファイルは **エージェント作業ルール** を記述します。
プロダクト仕様（料金体系・ドメインルール）の正本は [README.md](./README.md) です。
料金仕様は [README の料金ポリシー節](./README.md#pricing-policy-current) を参照してください。
用語の解釈は [用語集](./docs/glossary.md) を優先してください。

README参照マップ（章ベース）
	•	利用者向け基本挙動: [README.md](./README.md) の「2. 利用者から見た基本機能」
	•	料金仕様・細則: [README.md](./README.md) の「3. 料金仕様」
	•	レイヤ責務と依存方向: [README.md](./README.md) の「4. アーキテクチャ」
	•	ドメイン固有モデル: [README.md](./README.md) の「5. ドメインモデル（このプロジェクト固有）」
	•	同時実行・冪等・競合制御: [README.md](./README.md) の「6. 同時実行性・整合性」
	•	エラー体系と補正方針: [README.md](./README.md) の「7. エラーモデル」および [補正イベント最小設計](./docs/repair-events-minimal-design.md)
	•	性能上の意図的省略: [README.md](./README.md) の「8. 性能と運用上の扱い」

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
	•	仕様判断が必要なときは [README.md](./README.md) を優先する
	•	機能追加・仕様変更は「READMEで仕様を先に確定 → 実装 → テスト → README整合再確認」の順で進める
	•	PR/コミット前に、仕様変更を含む場合は `README.md` / `docs/*.md` / 実装の不整合がないことを確認する
	•	ドキュメント更新の確認順は `README.md` → [用語集](./docs/glossary.md) → 補助docs（例: [補正イベント最小設計](./docs/repair-events-minimal-design.md)）→ `AGENTS.md`
	•	レビュー結果は原則 `P1/P2/P3 + file:line + 再現条件 + 改善案` で記述する
	•	`CorruptedEventStream` はイベント列前提の破綻時のみ使い、通常競合・状態遷移の拒否は `InvalidPaymentOperation` を使う
	•	同時実行ロジック（CAS/先勝ち/冪等）に変更が入る場合は、並行実行のテストを最低1本追加する
	•	README の主要節は安定アンカーを付与し、`AGENTS.md` からはアンカー付き相対リンクで参照する
	•	コミット前チェックとして `scala-cli test . --server=false`・Markdownリンク整合・README整合を確認する
	•	仕様を更新したら、まず [README.md](./README.md) を更新し、必要なら `AGENTS.md` は参照リンクだけ調整する
	•	Markdown 内の参照は相対リンク（例: `[README](./README.md)`）で記述し、節参照は安定アンカー（`#pricing-policy-current` のような id）を使う
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
