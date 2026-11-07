#!/bin/sh
# Provisions the three DynamoDB tables required by the ESA v5 storage profile.
#
# journal  : aid (S) / seq_nr (N), no stream
# snapshot : aid (S) / skey (N), sparse GSI (aid, active_history_seq_nr), TTL attribute `ttl`
# head     : aid (S), Streams with NEW_IMAGE
#
# The store validates one `__config__` item per table at startup. The items are written here with one shared
# store_id so that the Write API and the Read Model Updater cannot disagree, and the library only has to validate them.
set -eu

AWS_REGION=${AWS_REGION:-ap-northeast-1}
AWS_DEFAULT_REGION=${AWS_DEFAULT_REGION:-$AWS_REGION}
export AWS_DEFAULT_REGION
export AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID:-x}
export AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY:-x}
export AWS_PAGER=""

DYNAMODB_ENDPOINT=${DYNAMODB_ENDPOINT:-localhost:4566}
ENDPOINT_URL="http://${DYNAMODB_ENDPOINT}"

JOURNAL_TABLE_NAME=${JOURNAL_TABLE_NAME:-cqrs-es-example-journal}
SNAPSHOT_TABLE_NAME=${SNAPSHOT_TABLE_NAME:-cqrs-es-example-snapshot}
HEAD_TABLE_NAME=${HEAD_TABLE_NAME:-cqrs-es-example-head}
SNAPSHOT_HISTORY_INDEX_NAME=${SNAPSHOT_HISTORY_INDEX_NAME:-snapshot-active-history-index}
STORE_ID=${STORE_ID:-00000000-0000-4000-8000-000000000000}

echo "DYNAMODB_ENDPOINT = ${ENDPOINT_URL}"
echo "JOURNAL_TABLE_NAME = ${JOURNAL_TABLE_NAME}"
echo "SNAPSHOT_TABLE_NAME = ${SNAPSHOT_TABLE_NAME}"
echo "HEAD_TABLE_NAME = ${HEAD_TABLE_NAME}"
echo "SNAPSHOT_HISTORY_INDEX_NAME = ${SNAPSHOT_HISTORY_INDEX_NAME}"

ddb() {
  aws dynamodb --endpoint-url "${ENDPOINT_URL}" "$@"
}

create_table() {
  table_name="$1"
  shift
  if ddb describe-table --table-name "${table_name}" >/dev/null 2>&1; then
    echo "table ${table_name} already exists"
  else
    ddb create-table --table-name "${table_name}" "$@"
  fi
  ddb wait table-exists --table-name "${table_name}"
}

create_table "${JOURNAL_TABLE_NAME}" \
  --attribute-definitions \
  AttributeName=aid,AttributeType=S \
  AttributeName=seq_nr,AttributeType=N \
  --key-schema \
  AttributeName=aid,KeyType=HASH \
  AttributeName=seq_nr,KeyType=RANGE \
  --provisioned-throughput ReadCapacityUnits=10,WriteCapacityUnits=10

create_table "${SNAPSHOT_TABLE_NAME}" \
  --attribute-definitions \
  AttributeName=aid,AttributeType=S \
  AttributeName=skey,AttributeType=N \
  AttributeName=active_history_seq_nr,AttributeType=N \
  --key-schema \
  AttributeName=aid,KeyType=HASH \
  AttributeName=skey,KeyType=RANGE \
  --provisioned-throughput ReadCapacityUnits=10,WriteCapacityUnits=10 \
  --global-secondary-indexes "[
    {
      \"IndexName\": \"${SNAPSHOT_HISTORY_INDEX_NAME}\",
      \"KeySchema\": [
        {\"AttributeName\": \"aid\", \"KeyType\": \"HASH\"},
        {\"AttributeName\": \"active_history_seq_nr\", \"KeyType\": \"RANGE\"}
      ],
      \"Projection\": {\"ProjectionType\": \"KEYS_ONLY\"},
      \"ProvisionedThroughput\": {\"ReadCapacityUnits\": 10, \"WriteCapacityUnits\": 10}
    }
  ]"

ddb update-time-to-live \
  --table-name "${SNAPSHOT_TABLE_NAME}" \
  --time-to-live-specification "Enabled=true,AttributeName=ttl" >/dev/null

create_table "${HEAD_TABLE_NAME}" \
  --attribute-definitions AttributeName=aid,AttributeType=S \
  --key-schema AttributeName=aid,KeyType=HASH \
  --provisioned-throughput ReadCapacityUnits=10,WriteCapacityUnits=10 \
  --stream-specification StreamEnabled=true,StreamViewType=NEW_IMAGE

put_config() {
  ddb put-item --table-name "$1" --item "$2" >/dev/null
}

put_config "${JOURNAL_TABLE_NAME}" \
  "{\"aid\":{\"S\":\"__config__\"},\"seq_nr\":{\"N\":\"0\"},\"store_id\":{\"S\":\"${STORE_ID}\"},\"layout_version\":{\"N\":\"1\"}}"
put_config "${SNAPSHOT_TABLE_NAME}" \
  "{\"aid\":{\"S\":\"__config__\"},\"skey\":{\"N\":\"0\"},\"store_id\":{\"S\":\"${STORE_ID}\"},\"layout_version\":{\"N\":\"1\"}}"
put_config "${HEAD_TABLE_NAME}" \
  "{\"aid\":{\"S\":\"__config__\"},\"store_id\":{\"S\":\"${STORE_ID}\"},\"layout_version\":{\"N\":\"1\"}}"

echo "dynamodb tables and configuration items are ready"
