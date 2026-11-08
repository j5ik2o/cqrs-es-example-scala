# 読み取りモデルのシーケンス番号を BIGINT に拡張する

ESA core-contract T-9 に合わせ、読み取りモデルの `last_processed_sequence_number` を 4 テーブルとも
`BIGINT NOT NULL` に拡張した。対象は次のとおり。

- `read_model_positions`
- `group_chats`
- `members`
- `messages`

## 背景

ESA core-contract T-9 は、シーケンス番号を JSON 数値として扱える範囲に収めることを求める。JavaScript などの
IEEE-754 倍精度浮動小数点数を使うクライアントは 2^53 - 1 を超える整数を正確に表現できないため、T-9 の上限は
2^53 - 1（9007199254740991）である。従来の `INT` は 2147483647 までしか保持できず、上限より先に桁あふれする。

## マイグレーション

`5_expand_last_processed_sequence_number.up.sql` が既存の 4 テーブルを `BIGINT NOT NULL` に ALTER する。
既存のマイグレーション 0〜3 は書き換えていない。MySQL の `INT` から `BIGINT` への変更は値をそのまま保持する。

アプリケーション側では `ReadModelSequenceNumber.validate` が 2^53 - 1 を超える値を拒否する。JDBC の読み書きは
`Long`（`setLong` / `getLong`）のままで、`Int` への切り詰めは行わない。

## down マイグレーションの注意

`5_expand_last_processed_sequence_number.down.sql` は列を `INT` に戻すため、2147483647 を超える値が 1 行でも
あると安全に戻せない。

- MySQL 8 の既定の strict モードでは、`ALTER TABLE` が
  `Out of range value for column 'last_processed_sequence_number'`（error 1264、バージョンによっては
  `Data truncated`）で失敗する。MySQL の DDL はトランザクションではないため、後続のテーブルで失敗すると、
  それより前に ALTER したテーブルは `INT` に戻ったまま残り、マイグレーションは部分適用かつ dirty になる。
- strict モードが無効な場合、値は 2147483647 に丸められる。読み取りモデルの位置がジャーナルより後ろにずれ、
  その後の投影が重複や欠落を誤判定するおそれがある。

down を実行する前に、次のクエリで最大値を確認する。

```sql
SELECT MAX(last_processed_sequence_number) FROM read_model_positions;
SELECT MAX(last_processed_sequence_number) FROM group_chats;
SELECT MAX(last_processed_sequence_number) FROM members;
SELECT MAX(last_processed_sequence_number) FROM messages;
```

いずれかが 2147483647 を超えている場合は down を実行しない。

## テスト

- `2147483648`、`9007199254740990`、`9007199254740991` を 4 テーブルへ書き込み、`Long` のまま読み戻せること。
- `9007199254740992` が `ReadModelSequenceNumber.OutOfRangeException` で拒否されること。

MySQL を使う結合テストは `READ_MODEL_INTEGRATION_URL` を設定した場合だけ実行される。設定しない場合、
`sbt test` ではキャンセルされる。
