# cqrs-es-example-scala

Scala 3 implementation of the shared CQRS and event sourcing example. The repository contains the command side, the
Write API, the Read API and the Read Model Updater. The docker compose based e2e is tracked in issue #4.

## Scope of this milestone

- Scala 3.6.4 and sbt 1.13.1.
- `event-store-adapter-scala` 5.1.0 with the v5 envelope API.
- Domain model for `GroupChat`, `Member` and `Message`, including replay from events and snapshots.
- Write API with the eight mutations and `healthCheck` from the common GraphQL schema.
- Read API with the six queries from the common GraphQL schema. It reads the MySQL read model and scopes every result
  by `userAccountId`.
- Read Model Updater that subscribes to the `head` table's DynamoDB Streams, deduplicates by
  `(aggregate_id, seq_nr)`, fills sequence gaps from the journal and records positions in `read_model_positions`.
- DynamoDB wiring for the `journal`, `snapshot` and `head` tables, including the snapshot GSI and the three
  `__config__` items that the event store validates at startup.
- MySQL schema and migrations for the read model.
- Unit tests for the domain, the command processor, the JSON serializers, the event store repository, the GraphQL
  APIs, the head stream decoder and the read model updater.

## Module layout

| Module | Responsibility |
|:--|:--|
| `modules/command/domain` | Aggregates, value objects, events and replay. No event store dependency |
| `modules/command/interface-adaptor-if` | Repository port used by the processor |
| `modules/command/processor` | Command processing, optimistic locking through the event store, snapshot policy |
| `modules/command/interface-adaptor-impl` | Event store repository, JSON serializers, event store factory and Write API |
| `modules/query/interface-adaptor` | MySQL read model and GraphQL Read API |
| `modules/rmu` | Head stream decoder, journal gap reader, read model projection and stream runner |
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

```sh
for migration in tools/migrate/migrations/*.up.sql; do
  mysql -u "$READ_MODEL_DATABASE_USER" -p"$READ_MODEL_DATABASE_PASSWORD" \
    -h localhost cqrs_es_example < "$migration"
done
```

`read_model_positions` stores the last applied `seq_nr` per aggregate. Every projection and the position update run in
one transaction, so a duplicate delivery is a no-op and a sequence gap fails instead of silently skipping.

### Read model environment variables

| Environment variable | Default |
|:--|:--|
| `READ_MODEL_DATABASE_URL` | `jdbc:mysql://localhost:3306/cqrs_es_example?useSSL=false&allowPublicKeyRetrieval=true` |
| `READ_MODEL_DATABASE_USER` | `root` |
| `READ_MODEL_DATABASE_PASSWORD` | `password` |

## Read Model Updater

The Read Model Updater subscribes to the `head` table's DynamoDB Streams, not the journal. Each head transition
carries the `seq_nr` and the event envelopes appended by that write. The updater:

- skips the reserved `__config__` item;
- deserializes the event payloads and applies them to the read model in ascending `seq_nr`;
- discards duplicates by `(aggregate_id, seq_nr)`;
- fills a sequence gap from the journal through `getEventsByIdSinceSeqNr` before applying the received event;
- records the processed position in `read_model_positions`.

Shard iterators start at `TRIM_HORIZON` when a shard is first seen. Replaying a record after a restart is safe because
the position table discards it.

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
| `STREAM_POLL_INTERVAL_MILLIS` | `1000` |
| `READ_MODEL_DATABASE_URL` | see above |
| `READ_MODEL_DATABASE_USER` | `root` |
| `READ_MODEL_DATABASE_PASSWORD` | `password` |

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

## Remaining work

- Issue #4: `dynamodb-setup` for the three tables, the `__config__` items and the head Streams; a MySQL service and
  the migration service; docker compose services for `write-api-server-1`, `read-model-updater-1` and
  `read-api-server-1`; the e2e script that writes through the Write API, waits for the read model to catch up and
  verifies the Read API; and the `e2e` CI job.
