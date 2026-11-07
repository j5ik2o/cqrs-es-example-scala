# リードモデル更新器の復旧

## 目的

リードモデル更新器（RMU）は、head テーブルの DynamoDB Streams を購読して MySQL のリードモデルを更新する。この文書は、イベントの適用に失敗したときと、購読が 24 時間のストリーム保持より遅れたときに、イベントを失わずに追いつく仕組みを説明する。根拠は ADR-0002、`event-store-adapter/docs/spec/storage/dynamodb.ja.md`（DY-12 から DY-15）、ハブの受け入れ基準である。

## 再試行

1 イベントの再試行は、回数の上限と経過時間の上限の両方で打ち切る。

| 設定 | 既定値 | 環境変数 |
|:--|:--|:--|
| 最大試行回数 | 5 | `STREAM_RETRY_MAX_ATTEMPTS` |
| 最大経過時間 | 30 秒 | `STREAM_RETRY_MAX_ELAPSED_MILLIS` |
| 初回待機 | 200 ミリ秒 | `STREAM_RETRY_INITIAL_DELAY_MILLIS` |
| 待機の上限 | 5 秒 | `STREAM_RETRY_MAX_DELAY_MILLIS` |

待機時間は試行ごとに 2 倍にし、上限で止め、full jitter でばらつかせる。ストリーム読み取りの空回り待機も 500 ミリ秒から 30 秒まで同じ形で伸ばす（`STREAM_POLL_BACKOFF_INITIAL_MILLIS` と `STREAM_POLL_BACKOFF_MAX_MILLIS`）。

失敗は 2 種類に分ける。

- 恒久的な失敗: ペイロードを復号できない、未対応のイベント種別、journal の欠番、リードモデルの制約違反（重複キー、外部キー、値の長さ超過）。同じイベントを再試行しても直らない。
- 一時的な失敗: データベース障害、ロック待ち、スロットリング、ネットワークエラー。再試行で直る可能性がある。

恒久的な失敗はすぐに復旧タスクへ記録する。一時的な失敗は試行回数または経過時間の上限まで再試行し、その後に記録する。記録自体に失敗した場合は呼び出し元へ失敗を返す。適用できないイベントを隠さないためである。

未適用のイベントがある集約は、位置より先へ進めない。位置ガードがこれを保証する。

## 復旧タスク（DLQ）

適用できなかったイベントは `read_model_recovery_tasks` に識別子だけで保存する。イベント本文は保存せず、復旧時に journal から読み直す。head のペイロードが壊れていても、journal が無事なら復旧できる。

| 列 | 内容 |
|:--|:--|
| `store_id` | 3 テーブルで共有するストア識別子（`__config__` から読む） |
| `projection_name` / `projection_version` | 投影の名前と版（`group-chat-read-model` / `1`） |
| `aggregate_id` / `seq_nr` | 失敗したイベントの集約と到達すべき位置 |
| `failure_type` | `event_payload`、`journal_gap`、`read_model_constraint` など |
| `error_message` | 失敗した位置とメッセージ |
| `status` | `pending` または `completed` |

主キーは `(store_id, projection_name, aggregate_id, seq_nr)` である。同じレコードが再配送されても、保留中のタスクを更新するだけで重複を作らない。

復旧は次の順で行う。

1. 集約の現在位置を読み直す。
2. 位置がタスクの `seq_nr` より小さければ、journal を昇順に読み、通常と同じ位置ガードで適用する。
3. 位置がタスクの `seq_nr` に到達したことを確認する。
4. タスクを `completed` にする。

位置を先に読むため、同じタスクを 2 回実行しても 2 回目は完了にするだけである。ローカルの RMU は起動時と 60 秒ごとに保留タスクを再実行する（`STREAM_RECOVERY_INTERVAL_MILLIS`）。1 回の実行で扱うタスクは 50 件までである（`STREAM_RECOVERY_MAX_TASKS_PER_RUN`）。失敗したタスクは `updated_at` を更新して、まだ試していないタスクの後ろへ回す。SQS を使う場合、メッセージは復旧ワーカーを起こすためだけに使い、イベント本文は載せない。

## 再同期

DY-15 に従い、RMU は次の場面で head テーブルを全件走査する。

- 起動時。位置がまったくない初回起動も含む。
- `TrimmedDataAccessException` を受けたとき。
- 最後にレコードを読んだ時刻が 24 時間より前のとき（`STREAM_RETENTION_HOURS`）。

走査は `ConsistentRead = true` で行い、`__config__` を除外する。各 head の `seq_nr` と投影位置を比べ、遅れている集約を journal から同じ位置ガードで補う。走査とストリームの両方から届いたイベントは、位置ガードで重複を捨てる。

起動時の走査が終わるまでストリームの購読を始めない。走査に失敗した場合は待機して再同期をやり直す。走査していない集約に復旧タスクが付かないまま購読を始めないためである。

`ExpiredIteratorException` はシャードイテレータの 15 分の有効期限が切れただけであり、レコードの消失ではない。最後に処理したシーケンス番号の次からイテレータを取り直す（`AFTER_SEQUENCE_NUMBER`）。全件再同期はしない。

## トランザクションと冪等性

- 1 イベントを 1 トランザクションで確定する。
- リードモデルの変更と `read_model_positions` の前進を同じトランザクションで確定する。
- 適用するのは「現在位置 + 1」のイベントだけである。
- 位置行は `SELECT ... FOR UPDATE` でロックする。行がなければ `INSERT ... ON DUPLICATE KEY UPDATE` で 1 文で作ってからロックする。2 人の読み手が同時に行を作るときのロック昇格の競合を避けるためである。
- MySQL のデッドロック（1213）とロック待ちタイムアウト（1205）はトランザクションごと再試行する。失敗したトランザクションはロールバック済みなので、イベントを 2 回適用しない。
- journal に欠番があれば位置を進めずに失敗する。未適用のイベントがある集約は先へ進めない。

## ローカル設定

Docker Compose では `tools/docker-compose/docker-compose-applications.yml` の環境変数で上書きできる。復旧タスクのテーブルは `tools/migrate/migrations/4_create_read_model_recovery_tasks.up.sql` で作る。

## テスト

`modules/rmu` のテストは次を網羅する。

- 重複した配送と古い配送を捨てる。
- 1 レコード内のイベントを `seq_nr` の昇順に適用し、古いイベントを捨てる。
- journal の欠番を補い、補えないときは位置を進めない。
- バッチの途中で失敗したとき、確定済みの前分を残して再試行で続きから進める。
- 再起動後に永続化した位置から再開する。
- 再試行の上限超過から復旧タスクを作り、journal から冪等に復旧する。
- 24 時間を超えた停止、`TrimmedDataAccessException`、`ExpiredIteratorException` の扱い。
- 同じイベントの同時適用と、位置行の同時作成。
- 復旧タスクの保存に失敗したとき、その失敗を返す。
- head テーブルの複数ページ走査。
- 復号できないペイロードを識別子だけで復旧タスクに記録する。

MySQL を使う結合テストは `READ_MODEL_INTEGRATION_URL` を設定したときだけ実行する。設定しない場合、`sbt test` ではキャンセルされる。

## 残作業

- Lambda のイベントソースマッピングと SQS のリソースはこのリポジトリのデプロイ資産にまだない。このリポジトリが持つのはローカルの RMU である。
- `RecoveryNotifier` は SQS の接続点である。ローカルの RMU は MySQL の復旧タスクを直接読む。
- compose の e2e は正常系を確認する。障害の注入は単体テストで確認する。
