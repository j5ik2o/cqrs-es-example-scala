# cqrs-es-example-scala

Scala 3 implementation of the shared CQRS and event sourcing example. The repository contains the command side, the
Write API, the Read API and the Read Model Updater, plus a docker compose based e2e that runs the whole flow against
LocalStack and MySQL.

## Scope of this milestone

- Scala 3.6.4 and sbt 1.13.1.
- `event-store-adapter-scala` 5.1.0 with the v5 envelope API.
- Domain model for `GroupChat`, `Member` and `Message`, including replay from events and snapshots.
- Write API with the eight mutations and `healthCheck` from the common GraphQL schema.
- Read API with the six queries from the common GraphQL schema. It reads the MySQL read model and scopes every result
  by `userAccountId`.
- Read Model Updater that subscribes to the `head` table's DynamoDB Streams, deduplicates by
  `(aggregate_id, seq_nr)`, fills sequence gaps from the journal and records positions in `read_model_positions`.
- Read Model Updater recovery: bounded retries with exponential backoff and full jitter, durable recovery tasks in
  `read_model_recovery_tasks` replayed from the journal, and DY-15 resynchronisation. See
  [`docs/RMU_RECOVERY.ja.md`](docs/RMU_RECOVERY.ja.md).
- Lambda and SQS deployment assets: a CloudFormation template for the head stream event source mapping, the OnFailure
  queue, the recovery worker and the scheduled resync worker, plus the package build and apply scripts. See
  [`deploy/README.ja.md`](deploy/README.ja.md). The CI validates the assets but does not deploy them.
- DynamoDB wiring for the `journal`, `snapshot` and `head` tables, including the snapshot GSI and the three
  `__config__` items that the event store validates at startup.
- MySQL schema and migrations for the read model.
- Unit tests for the domain, the command processor, the JSON serializers, the event store repository, the GraphQL
  APIs, the head stream decoder, the read model updater and its recovery paths.

## Module layout

| Module | Responsibility |
|:--|:--|
| `modules/command/domain` | Aggregates, value objects, events and replay. No event store dependency |
| `modules/command/interface-adaptor-if` | Repository port used by the processor |
| `modules/command/processor` | Command processing, optimistic locking through the event store, snapshot policy |
| `modules/command/interface-adaptor-impl` | Event store repository, JSON serializers, event store factory and Write API |
| `modules/query/interface-adaptor` | MySQL read model and GraphQL Read API |
| `modules/rmu` | Head stream decoder, journal reader, read model applier, retry policy, recovery tasks, SQS notifier and the resync/poll loop |
| `modules/lambda` | Lambda handlers, the SQS recovery worker, the resync worker and the Lambda environment configuration |
| `modules/bootstrap` | Write API, Read API and Read Model Updater servers, plus environment configuration |

## Requirements

- JDK 17 or later. The CI uses Temurin 17.
- sbt 1.13.1. The launcher reads the version from `project/build.properties`.
- MySQL 8 for the read model.

## Build and test

```sh
sbt compile
sbt test
sbt lint
sbt lambda/assembly
```

`sbt lint` runs `scalafmtCheckAll`, `scalafmtSbtCheck` and `scalafixAll --check`. `sbt fmt` rewrites the sources.

## GraphQL Write API

The server exposes GraphQL at `/graphql`. The default port is `48080`, and `WRITE_API_HOST` / `WRITE_API_PORT` change
it. The schema has the eight mutations `createGroupChat`, `deleteGroupChat`, `renameGroupChat`, `addMember`,
`removeMember`, `postMessage`, `editMessage` and `deleteMessage`, plus the `healthCheck` query.

```graphql
mutation {
  createGroupChat(input: { name: "team", executorId: "UserAccount-01ARZ3NDEKTSV4RRFFQ69G5FAV" }) {
    groupChatId
  }
}
```

```graphql
query {
  healthCheck
}
```

`executorId`, `groupChatId` and `messageId` accept both the prefixed form (`GroupChat-<ulid>`,
`UserAccount-<ulid>`) and the raw ULID.

## GraphQL Read API

The Read API exposes GraphQL at `/graphql`. The default port is `48082`, and `READ_API_HOST` / `READ_API_PORT` change
it. It reads only the MySQL read model. Every query takes `userAccountId` and returns only the data that the account
can see.

```graphql
query {
  getGroupChats(userAccountId: "UserAccount-01ARZ3NDEKTSV4RRFFQ69G5FAV") {
    id
    name
    ownerId
    createdAt
    updatedAt
  }
}
```

```graphql
query {
  getMessages(groupChatId: "GroupChat-01ARZ3NDEKTSV4RRFFQ69G5FAV", userAccountId: "UserAccount-01ARZ3NDEKTSV4RRFFQ69G5FAV") {
    id
    userAccountId
    text
    createdAt
    updatedAt
  }
}
```

The schema has `getGroupChat`, `getGroupChats`, `getMember`, `getMembers`, `getMessage` and `getMessages`, plus the
types `GroupChat`, `Member`, `Message` and the scalar `NaiveDateTime`.

## Read model

The read model lives in MySQL and is separate from the DynamoDB journal, snapshot and head tables. The migrations are
in `tools/migrate/migrations` and run in numeric order:

| File | Table |
|:--|:--|
| `0_create_read_model_positions.up.sql` | `read_model_positions` |
| `1_create_group_chats.up.sql` | `group_chats` |
| `2_create_members.up.sql` | `members` |
| `3_create_messages.up.sql` | `messages` |
| `4_create_read_model_recovery_tasks.up.sql` | `read_model_recovery_tasks` |

```sh
for migration in tools/migrate/migrations/*.up.sql; do
  mysql -u "$READ_MODEL_DATABASE_USER" -p"$READ_MODEL_DATABASE_PASSWORD" \
    -h localhost cqrs_es_example < "$migration"
done
```

`read_model_positions` stores the last applied `seq_nr` per aggregate. Every projection and the position update run in
one transaction, so a duplicate delivery is a no-op and a sequence gap fails instead of silently skipping.
`read_model_recovery_tasks` stores events that could not be applied. The event body is re-read from the journal when
the task is replayed.

### Read model environment variables

| Environment variable | Default |
|:--|:--|
| `READ_MODEL_DATABASE_URL` | `jdbc:mysql://localhost:3306/cqrs_es_example?useSSL=false&allowPublicKeyRetrieval=true` |
| `READ_MODEL_DATABASE_USER` | `root` |
| `READ_MODEL_DATABASE_PASSWORD` | `password` |

## Read Model Updater

The Read Model Updater subscribes to the `head` table's DynamoDB Streams, not the journal. Each head transition
carries the `seq_nr` and the event envelopes appended by that write. The updater:

- skips the reserved `__config__` item and heads of other aggregate types;
- deserializes the event payloads and applies them to the read model in ascending `seq_nr`;
- discards duplicates by `(aggregate_id, seq_nr)`;
- fills a sequence gap from the journal before applying the received event;
- records the processed position in `read_model_positions`;
- retries a retryable failure with an attempt limit, a wall-clock limit and full-jitter exponential backoff;
- records a permanent failure, or a retry that exhausted its budget, in `read_model_recovery_tasks` and replays it
  from the journal later.

Shard iterators start at `TRIM_HORIZON` when a shard is first seen. Replaying a record after a restart is safe because
the position table discards it. An expired iterator resumes after the last processed sequence number. A trimmed stream
runs a full resync, and so does a reader whose polling has not succeeded for longer than the 24 hour stream retention. A
full resync resumes the head Streams first, then scans every head with strong consistency in parallel and fills the lag
from the journal while polling continues.

The recovery table is written by identifiers only. Recovery reads the position first, replays the journal in ascending
order with the normal position guard, confirms the target position, and only then marks the task complete. A recovery
run handles a bounded number of tasks, and a failed task moves behind tasks that have not been attempted yet.

### Read Model Updater environment variables

| Environment variable | Default |
|:--|:--|
| `HEAD_TABLE_NAME` | `cqrs-es-example-head` |
| `JOURNAL_TABLE_NAME` | `cqrs-es-example-journal` |
| `SNAPSHOT_TABLE_NAME` | `cqrs-es-example-snapshot` |
| `SNAPSHOT_ACTIVE_HISTORY_INDEX_NAME` | `snapshot-active-history-index` |
| `AWS_REGION` | `ap-northeast-1` |
| `AWS_DYNAMODB_ENDPOINT_URL` / `AWS_ENDPOINT_URL` | unset |
| `STREAM_MAX_ITEM_COUNT` | `100` |
| `STREAM_RETRY_MAX_ATTEMPTS` | `5` |
| `STREAM_RETRY_MAX_ELAPSED_MILLIS` | `30000` |
| `STREAM_RETRY_INITIAL_DELAY_MILLIS` | `200` |
| `STREAM_RETRY_MAX_DELAY_MILLIS` | `5000` |
| `STREAM_POLL_BACKOFF_INITIAL_MILLIS` | `500` |
| `STREAM_POLL_BACKOFF_MAX_MILLIS` | `30000` |
| `STREAM_RECOVERY_INTERVAL_MILLIS` | `60000` |
| `STREAM_RECOVERY_MAX_TASKS_PER_RUN` | `50` |
| `STREAM_RETENTION_HOURS` | `24` |
| `RECOVERY_QUEUE_URL` | unset (the notifier is disabled) |
| `RECOVERY_SQS_REGION` | `AWS_REGION`, else `ap-northeast-1` |
| `RECOVERY_SQS_ENDPOINT_URL` | `AWS_ENDPOINT_URL`, else unset |
| `READ_MODEL_DATABASE_URL` | see above |
| `READ_MODEL_DATABASE_USER` | `root` |
| `READ_MODEL_DATABASE_PASSWORD` | `password` |

## Lambda and SQS deployment assets

`deploy/` holds the CloudFormation template, the Lambda package build script and the apply and rollback runbook. The
template defines the head stream event source mapping with `BisectBatchOnFunctionError` and
`ReportBatchItemFailures`, an OnFailure SQS queue, an SQS-triggered recovery worker and a scheduled resync worker.
The recovery notification carries identifiers only and the worker re-reads the journal.

```sh
./deploy/build-lambda-package.sh
./deploy/apply-rmu-deployment.sh --parameters deploy/parameters.json
```

The CI validates the template and builds the package. It does not deploy. See
[`deploy/README.ja.md`](deploy/README.ja.md) for the full runbook.

## Running the applications

### Write API with the in-memory event store

```sh
EVENT_STORE_BACKEND=memory sbt "bootstrap/run"
```

The memory store keeps data in the process and is intended for local checks and tests. It does not survive restarts.

### Write API with DynamoDB

Create the three tables and the snapshot GSI first. The event store validates the three `__config__` items at startup
and fails with a configuration error when the items are missing or do not share the same `store_id` and
`layout_version=1`.

```sh
export EVENT_STORE_BACKEND=dynamodb
export AWS_REGION=ap-northeast-1
export AWS_DYNAMODB_ENDPOINT_URL=http://localhost:4566
export JOURNAL_TABLE_NAME=cqrs-es-example-journal
export SNAPSHOT_TABLE_NAME=cqrs-es-example-snapshot
export HEAD_TABLE_NAME=cqrs-es-example-head
export SNAPSHOT_ACTIVE_HISTORY_INDEX_NAME=snapshot-active-history-index
sbt "bootstrap/run"
```

### Read API

```sh
export READ_MODEL_DATABASE_URL='jdbc:mysql://localhost:3306/cqrs_es_example?useSSL=false&allowPublicKeyRetrieval=true'
sbt "bootstrap/runMain com.github.j5ik2o.cqrs.es.example.scala.bootstrap.ReadApiServer"
```

### Read Model Updater

```sh
export AWS_REGION=ap-northeast-1
export AWS_DYNAMODB_ENDPOINT_URL=http://localhost:4566
export HEAD_TABLE_NAME=cqrs-es-example-head
export READ_MODEL_DATABASE_URL='jdbc:mysql://localhost:3306/cqrs_es_example?useSSL=false&allowPublicKeyRetrieval=true'
sbt "bootstrap/runMain com.github.j5ik2o.cqrs.es.example.scala.bootstrap.ReadModelUpdaterApp"
```

### Write API environment variables

| Environment variable | Default |
|:--|:--|
| `WRITE_API_HOST` | `0.0.0.0` |
| `WRITE_API_PORT` | `48080` |
| `EVENT_STORE_BACKEND` | `dynamodb` (`memory` for local checks) |
| `JOURNAL_TABLE_NAME` | `cqrs-es-example-journal` |
| `SNAPSHOT_TABLE_NAME` | `cqrs-es-example-snapshot` |
| `HEAD_TABLE_NAME` | `cqrs-es-example-head` |
| `SNAPSHOT_ACTIVE_HISTORY_INDEX_NAME` | `snapshot-active-history-index` |
| `AWS_REGION` | `ap-northeast-1` |
| `AWS_DYNAMODB_ENDPOINT_URL` / `AWS_ENDPOINT_URL` | unset |

## Event and snapshot envelopes

Events and snapshots follow the v5 contract:

- The event envelope carries `aggregate_id`, `seq_nr`, `occurred_at`, `manifest` and `payload`.
- The manifest is `<event-type>@<schema-version>`, for example `group-chat-created@1` or
  `group-chat-message-edited@1`.
- The payload contains only domain data and a `type` field that the serializer uses to choose the event class. It does
  not contain the aggregate id, the sequence number, the occurrence time or the manifest.
- The snapshot manifest is `group-chat@1`.
- The snapshot policy writes a snapshot on creation and every 10 events after that.

## Docker compose e2e

`tools/docker-compose` starts LocalStack (DynamoDB and DynamoDB Streams), MySQL, the migrations, the DynamoDB setup and
the three applications. `tools/e2e-test` runs the verification script.

```sh
# Build the application and e2e images.
./tools/scripts/docker-compose-build.sh

# Start LocalStack, MySQL, the migrations, dynamodb-setup and the three applications.
./tools/scripts/docker-compose-up.sh

# Run the full e2e and tear the stack down afterwards.
./tools/scripts/docker-compose-e2e-test.sh

# Stop the stack and remove its volumes.
./tools/scripts/docker-compose-down.sh
```

The e2e script waits with a deadline for the Write API's `healthCheck` and the Read API's `__typename`, then writes a
group chat, a member and a message, and polls the Read API until the read model catches up. It then renames the group
chat, edits and deletes the message, removes the member and deletes the group chat, checking every result by polling.
No fixed sleep decides success. A GraphQL `errors` response from a mutation fails the run, and a timeout prints the last
response and exits non-zero.

| Service | Host port | Purpose |
|:--|:--|:--|
| `write-api-server-1` | 48080 | Write API GraphQL at `/graphql` |
| `read-model-updater-1` | none | head Streams subscriber |
| `read-api-server-1` | 48082 | Read API GraphQL at `/graphql` |
| `localstack` | 24566 | DynamoDB and DynamoDB Streams |
| `mysql` | 23306 | read model |

The `dynamodb-setup` service creates the three tables from the ESA v5 storage profile:

| Table | Key | GSI | Streams |
|:--|:--|:--|:--|
| `cqrs-es-example-journal` | `aid` (S) / `seq_nr` (N) | none | disabled |
| `cqrs-es-example-snapshot` | `aid` (S) / `skey` (N) | `(aid, active_history_seq_nr)`, `KEYS_ONLY` | disabled |
| `cqrs-es-example-head` | `aid` (S) | none | enabled, `NEW_IMAGE` |

It also writes one `__config__` item per table with the same `store_id` and `layout_version=1`, enables TTL attribute
`ttl` on the snapshot table, and leaves the journal Streams disabled. The store validates the three items at startup.

The GitHub Actions workflow has four jobs: `lint`, `test`, `deploy-assets` and `e2e`. The `deploy-assets` job
validates the CloudFormation template and builds the Lambda package. The `e2e` job runs
`tools/scripts/docker-compose-e2e-test.sh` against the built images and LocalStack. The `main` branch requires all four
checks.

## Remaining design decisions

The following are not implemented and are reported in the Pull Request as design questions:

- The deployment reads the MySQL credentials from CloudFormation parameters. A production stack should resolve them
  from Secrets Manager or SSM Parameter Store.
- The CloudWatch alarm thresholds, the recovery worker concurrency and the per-run task budget have not been tuned
  against a production workload.
- The fault-injection coverage is at the handler and unit level. There is no LocalStack e2e that runs the Lambda and
  SQS resources end to end.
