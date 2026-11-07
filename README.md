# cqrs-es-example-scala

Scala 3 implementation of the shared CQRS and event sourcing example. This repository currently contains the command
side and the Write API. The Read API, the Read Model Updater and the docker-compose based e2e are tracked separately.

## Scope of this milestone

- Scala 3.6.4 and sbt 1.13.1.
- `event-store-adapter-scala` 5.1.0 with the v5 envelope API.
- Domain model for `GroupChat`, `Member` and `Message`, including replay from events and snapshots.
- Write API with the eight mutations and `healthCheck` from the common GraphQL schema.
- DynamoDB wiring for the `journal`, `snapshot` and `head` tables, including the snapshot GSI and the three
  `__config__` items that the event store validates at startup.
- Unit tests for the domain, the command processor, the JSON serializers, the event store repository and the GraphQL
  API.

The Read API is issue #3. The Read Model Updater, docker compose, LocalStack setup and the end-to-end test are issue
#4.

## Module layout

| Module | Responsibility |
|:--|:--|
| `modules/command/domain` | Aggregates, value objects, events and replay. No event store dependency |
| `modules/command/interface-adaptor-if` | Repository port used by the processor |
| `modules/command/processor` | Command processing, optimistic locking through the event store, snapshot policy |
| `modules/command/interface-adaptor-impl` | Event store repository, JSON serializers, event store factory and GraphQL API |
| `modules/query/interface-adaptor` | Placeholder for the Read API (#3) |
| `modules/rmu` | Placeholder for the Read Model Updater (#4) |
| `modules/bootstrap` | Write API server and environment configuration |

## Requirements

- JDK 17 or later. The CI uses Temurin 17.
- sbt 1.13.1. The launcher reads the version from `project/build.properties`.

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

## Running the Write API

### With the in-memory event store

```sh
EVENT_STORE_BACKEND=memory sbt "bootstrap/run"
```

The memory store keeps data in the process and is intended for local checks and tests. It does not survive restarts.

### With DynamoDB

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

The table names and the GSI name are configuration values. The logical roles are fixed by the event store: `journal`
stores events by `aid` and `seq_nr`, `snapshot` stores the current state under `skey=0` and the history, and `head`
stores the latest `seq_nr` per aggregate and feeds DynamoDB Streams.

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

- Issue #3: Read API and the MySQL read model.
- Issue #4: Read Model Updater on the `head` table's DynamoDB Streams, `dynamodb-setup`, docker compose, LocalStack,
  and the e2e that runs Write, RMU and Read in order.
