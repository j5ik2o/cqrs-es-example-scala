# RMU 復旧のデプロイ資産

このディレクトリには、リードモデル更新器（RMU）の復旧を AWS で動かすための CloudFormation テンプレート、Lambda 配布物のビルドスクリプト、適用とロールバックの手順を置く。テンプレートは head テーブルの Streams を購読する Lambda、OnFailure の SQS、復旧タスクを再生する SQS ワーカー、全件再同期を実行するワーカーを定義する。このリポジトリの CI はテンプレートと配布物を検証するだけで、実際のデプロイは行わない。

## なぜ CloudFormation なのか

このリポジトリには Terraform、SAM、CDK のいずれもない。AWS 側の構成を管理する資産はまだなく、デプロイに関するものは Docker Compose とシェルスクリプトだけである。そこで、追加のツールを入れずに 1 ファイルで構成を再現できる CloudFormation の JSON テンプレートを選んだ。JSON にしたのは、テストからそのまま読み、設定値を検査するためである。

## 構成

| リソース | 役割 |
|:--|:--|
| `RmuFunction` | head テーブルの Streams を購読する Lambda。ハンドラは `LambdaReadModelUpdater::handleRequest`。 |
| `RmuEventSourceMapping` | head Streams と RMU をつなぐイベントソースマッピング。 |
| `OnFailureQueue` / `OnFailureDlq` | 再試行を使い切ったストリームレコードを受けるキューと、その DLQ。 |
| `RecoveryQueue` / `RecoveryDlq` | 復旧ワーカーを起動するメッセージのキューと、その DLQ。 |
| `RecoveryWorkerFunction` | SQS で起動し、MySQL の `read_model_recovery_tasks` を journal から再生する Lambda。ハンドラは `LambdaRecoveryWorker::handleRequest`。 |
| `RecoveryWorkerEventSourceMapping` | `RecoveryQueue` と復旧ワーカーをつなぐイベントソースマッピング。 |
| `RecoveryWorkerSchedule` | 通知が届かなくても未処理タスクを処理する EventBridge の定期実行。既定は 5 分ごと。 |
| `ResyncWorkerFunction` | head テーブルを全件 Scan し、journal から遅れを埋める Lambda。ハンドラは `LambdaResyncWorker::handleRequest`。 |
| `ResyncWorkerSchedule` | 全件再同期の EventBridge の定期実行。既定は 1 日ごと。 |

DLQ は、配信に失敗したメッセージを退避するキューである。3 つの Lambda はすべて同じ配布物を使う。

## Lambda 配布物のビルドとアップロード

`deploy/build-lambda-package.sh` が `sbt lambda/assembly` を実行し、`modules/lambda/target/scala-3.6.4/cqrs-es-example-scala-lambda.jar` を作る。JDK 17 で実行する。この jar には 3 つのハンドラと依存ライブラリがすべて入る。依存に HTTP サーバーを含めないよう、ハンドラは `modules/bootstrap` ではなく `modules/lambda` に置いてある。

```sh
./deploy/build-lambda-package.sh
```

S3 にアップロードするときは `--upload` とバケットを指定する。

```sh
./deploy/build-lambda-package.sh \
  --upload \
  --bucket my-lambda-artifacts \
  --key rmu/rmu-lambda.jar
```

スクリプトはビルド後に jar のパス、サイズ、SHA-256 を表示する。`--skip-build` を付けると、既存の jar をそのまま使う。`--output` で jar の複製先を変えられる。アップロードしたバケットとキーを、テンプレートの `CodeS3Bucket` と `CodeS3Key` に渡す。

この jar は約 120 MB ある。Lambda の展開後の上限は 250 MB であるため、S3 に置いて `CodeS3Bucket` と `CodeS3Key` で参照する。50 MB を超える配布物をインラインで渡すことはできない。

Lambda を更新するときは、新しい jar を別のキーにアップロードし、`CodeS3Key` を変えてスタックを更新する。同じキーを上書きすると、CloudFormation はコードの変更を検知しないため、スタックは古い配布物のままになる。

## 前提

- head、journal、snapshot の 3 テーブルと、read model の MySQL スキーマが作成済みである。テンプレートはテーブルもスキーマも作らない。
- head テーブルで DynamoDB Streams が有効になっている。ストリームの ARN を `HeadStreamArn` に渡す。
- MySQL のリードモデルへ到達できる。VPC 内の MySQL を使う場合は `VpcId`、`SubnetIds`、`SecurityGroupIds` を指定する。パブリックに到達できる場合は空のままでよい。
- AWS CLI と `jq` が使える。`jq` がない場合は `python3` を使う。
- デプロイ先のアカウントとリージョンに切り替えてある。

## 設定の対応表

issue #11 で求められた設定と、テンプレート上の指定は次のとおり。

| 項目 | テンプレートの指定 | 値 |
|:--|:--|:--|
| `BisectBatchOnFunctionError` | `RmuEventSourceMapping.Properties.BisectBatchOnFunctionError` | `true` |
| `ReportBatchItemFailures` | `RmuEventSourceMapping.Properties.FunctionResponseTypes` | `["ReportBatchItemFailures"]` |
| OnFailure の SQS | `RmuEventSourceMapping.Properties.DestinationConfig.OnFailure.Destination` | `OnFailureQueue` の ARN |
| 再試行回数 | `RmuEventSourceMapping.Properties.MaximumRetryAttempts` | 既定 3 |
| レコードの保持期間 | `RmuEventSourceMapping.Properties.MaximumRecordAgeInSeconds` | 既定 604800 秒（7 日） |
| 関数のタイムアウト | `RmuFunction.Properties.Timeout` | 既定 300 秒 |
| 復旧キューの可視性タイムアウト | `RecoveryQueue.Properties.VisibilityTimeout` | 既定 1800 秒 |
| 復旧ワーカーの起動 | `RecoveryWorkerEventSourceMapping.Properties.BatchSize` | `1` |
| 1 回に再生する復旧タスク数 | `STREAM_RECOVERY_MAX_TASKS_PER_RUN` | 既定 50 |

`BisectBatchOnFunctionError` は失敗したレコードの範囲を分割し、`ReportBatchItemFailures` は返した `itemIdentifier` のレコードだけを再配信する。両方を有効にすると、1 件の壊れたレコードで同じバッチの正常なレコードが失敗しない。

`MaximumRecordAgeInSeconds` を 7 日にしても、Streams の保持期間は 24 時間である。24 時間を超えて止まった場合は、再同期ワーカーが head テーブルを Scan して追いつく。`StartingPosition` は `TRIM_HORIZON` にした。位置ガードが重複を捨てるため、再配信されても結果は変わらない。

## 適用手順

`parameters.example.json` をコピーし、値を埋める。

```sh
cp deploy/parameters.example.json deploy/parameters.json
```

`CodeS3Bucket` と `CodeS3Key`、`HeadStreamArn`、`ReadModelDatabaseUrl` などを実際の値にする。`VpcId` を使わない場合は空のままでよい。空の値は適用時に無視され、テンプレートの既定値が使われる。

まずテンプレートの構文だけを確認する。この段階では AWS を呼ばない。

```sh
./deploy/apply-rmu-deployment.sh --parameters deploy/parameters.json
```

問題がなければスタックを作成する。

```sh
./deploy/apply-rmu-deployment.sh --apply --stack-name cqrs-es-example-rmu --parameters deploy/parameters.json
```

スクリプトは `aws cloudformation validate-template` のあと、`aws cloudformation deploy` を `CAPABILITY_IAM` 付きで実行する。`--apply` を付けない限りデプロイしない。

## 適用後の確認

イベントソースマッピングの設定を確認する。

```sh
aws lambda get-event-source-mapping \
  --uuid "$(aws cloudformation describe-stack-resource \
    --stack-name cqrs-es-example-rmu \
    --logical-resource-id RmuEventSourceMapping \
    --query 'StackResourceDetail.PhysicalResourceId' --output text)" \
  --query '{State:State,Bisect:BisectBatchOnFunctionError,ResponseTypes:FunctionResponseTypes,Retries:MaximumRetryAttempts,OnFailure:DestinationConfig.OnFailure.Destination}'
```

次の値になっていることを確かめる。

- `BisectBatchOnFunctionError` が `true`
- `FunctionResponseTypes` に `ReportBatchItemFailures` が含まれる
- `DestinationConfig.OnFailure.Destination` が `OnFailureQueue` の ARN
- `State` が `Enabled`

関数のタイムアウトと復旧キューの可視性タイムアウトを確認する。

```sh
aws lambda get-function-configuration \
  --function-name cqrs-es-example-rmu-rmu --query 'Timeout'

aws sqs get-queue-attributes \
  --queue-url "$(aws cloudformation describe-stacks \
    --stack-name cqrs-es-example-rmu \
    --query 'Stacks[0].Outputs[?OutputKey==`RecoveryQueueUrl`].OutputValue' --output text)" \
  --attribute-names VisibilityTimeout RedrivePolicy
```

## 復旧タスクの運用手順

RMU はイベントを適用できないとき、識別子だけを MySQL の `read_model_recovery_tasks` に書き、`RECOVERY_QUEUE_URL` が設定されていれば `RecoveryQueue` へ起動メッセージを送る。メッセージ本文は識別子だけで、イベント本文を含まない。復旧ワーカーはキューからメッセージを受け取ると、未処理タスクを `STREAM_RECOVERY_MAX_TASKS_PER_RUN` 件ずつ最大 10 バッチ処理する。本文は使わない。

ワーカーが失敗として返したメッセージは、キューの `maxReceiveCount`（既定 5）に達するまで再配信される。達したメッセージは `RecoveryDlq` に移る。

### 復旧 DLQ にメッセージが移ったとき

1. `RecoveryDlq` のメッセージ本文で、対象の集約とシーケンス番号を確認する。
2. MySQL の `read_model_recovery_tasks` で同じタスクが `pending` のまま残っていることを確認する。`error_message` と `failure_type` で原因の種類を見る。
3. 原因を取り除く。journal にイベントがあるか、リードモデルの制約違反が解消したかを確認する。
4. ワーカーをもう一度実行する。SQS のメッセージを再送するか、次のコマンドで直接起動する。ワーカーはメッセージ本文を使わないため、空のイベントでも未処理タスクを再生する。未処理タスクが残ると、このコマンドは関数エラーを返す。

```sh
aws lambda invoke \
  --function-name cqrs-es-example-rmu-rmu-recovery-worker \
  --payload '{"Records":[]}' \
  --cli-binary-format raw-in-base64-out \
  /tmp/recovery-worker-response.json
```

5. `read_model_recovery_tasks` から対象の行が消え、`read_model_positions` が対象のシーケンス番号まで進んだことを確認する。

### OnFailure キューにレコードが移ったとき

`OnFailureQueue` には、Lambda の再試行を使い切ったストリームレコードが入る。復旧タスクの記録自体に失敗した場合もここに来る。メッセージはストリームレコードの JSON なので、まず原因を特定する。記録に失敗しただけなら、同じレコードを再送すれば RMU が復旧タスクを作り直す。

### 全件再同期を実行するとき

イベントソースマッピングを 24 時間以上止めた場合や、保持期間を過ぎたレコードが残った場合は、再同期ワーカーを直接起動する。

```sh
aws lambda invoke \
  --function-name cqrs-es-example-rmu-rmu-resync-worker \
  --payload '{}' \
  --cli-binary-format raw-in-base64-out \
  /tmp/resync-worker-response.json
cat /tmp/resync-worker-response.json
```

応答には `heads`、`skipped`、`advanced`、`failed` が入る。位置ガードがあるため、繰り返し実行しても結果は変わらない。

### 監視

次のメトリクスを見る。

- Lambda の `Errors`、`Throttles`、`Duration`、`IteratorAge`
- `OnFailureQueue` と `RecoveryQueue` の `ApproximateNumberOfMessagesVisible`、`ApproximateAgeOfOldestMessage`
- 両方の DLQ の `ApproximateNumberOfMessagesVisible`
- `RecoveryWorkerFunction` と `ResyncWorkerFunction` の `Errors`

DLQ の深さが増えたら、上の復旧 DLQ の手順に進む。

## ロールバック

スタックを削除すると、テンプレートが作ったキューと Lambda も削除される。キューにメッセージが残っている場合は先に退避する。

```sh
aws cloudformation delete-stack --stack-name cqrs-es-example-rmu
```

設定だけを戻す場合は、テンプレートのパラメータを変えて `--apply` を再実行する。`RmuEventSourceMapping` の変更はスタック更新で反映される。

## テスト

`lambda` モジュールのテストが次を検査する。

- `RmuDeploymentTemplateSpec` がテンプレートを読み、`BisectBatchOnFunctionError`、`ReportBatchItemFailures`、OnFailure の SQS、再試行回数、タイムアウト、DLQ、定期実行、ハンドラ名を検査する。
- `LambdaReadModelUpdaterSpec` が部分バッチ応答を検査する。復旧タスクに記録したレコードは再配信せず、同じ集約の後続レコードだけを再配信する。シーケンス番号がないレコードはバッチ全体を失敗させる。
- `LambdaRecoveryWorkerSpec` が復旧の成功、失敗時の再配信、手動起動時の失敗、バッチ上限を検査する。
- `LambdaRecordAdapterSpec` が Lambda の DynamoDB レコードを SDK v2 のレコードに変換できることを検査する。
- `SqsRecoveryNotifierSpec` が通知本文に識別子だけが入り、イベント本文とエラーメッセージが入らないことを検査する。

`LambdaRmuConfigSpec` が環境変数の解析を検査する。`ReadApiConfigSpec` は `RmuConfig` の既定値と上書きを引き続き検査する。

CI の `deploy-assets` ジョブは、`cfn-lint` でテンプレートを検査し、シェルスクリプトの構文を確認し、`sbt lambda/assembly` で配布物を作る。jar に 3 つのハンドラのクラスが入っていることも確認する。

## 残課題

- MySQL のユーザー名とパスワードはテンプレートの引数で渡している。本番では Secrets Manager か SSM Parameter Store の参照に置き換える。
- 実際の AWS 環境へのデプロイと、CloudWatch アラームのしきい値の調整。
- 復旧ワーカーの同時実行数と、1 回に処理するタスク数の調整。
- 障害を注入した e2e。現在はハンドラの単体テストで、復旧タスクの記録、部分バッチ応答、再配信の要求を確認している。LocalStack 上で Lambda と SQS を動かす e2e はまだない。
