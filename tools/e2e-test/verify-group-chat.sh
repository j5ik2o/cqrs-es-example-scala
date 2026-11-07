#!/usr/bin/env bash
# End-to-end check: Write API -> DynamoDB (journal, snapshot, head) -> head Streams -> RMU -> MySQL -> Read API.
#
# Every wait is a poll with a deadline. A fixed sleep is not used to decide success.
set -uo pipefail

WRITE_API="${WRITE_API_SERVER_BASE_URL:-http://localhost:48080/graphql}"
READ_API="${READ_API_SERVER_BASE_URL:-http://localhost:48082/graphql}"
TIMEOUT_SECONDS="${E2E_TIMEOUT_SECONDS:-180}"
POLL_INTERVAL_SECONDS="${E2E_POLL_INTERVAL_SECONDS:-1}"

ADMIN_ID="UserAccount-01ARZ3NDEKTSV4RRFFQ69G5FAV"
MEMBER_ID="UserAccount-01ARZ3NDEKTSV4RRFFQ69G5FAW"
GROUP_CHAT_NAME="team"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

now() {
  date +%s
}

post_graphql() {
  local url="$1"
  local payload="$2"
  curl -sS -X POST -H "Content-Type: application/json" -d "$payload" "$url"
}

write_mutation() {
  local query="$1"
  local variables="$2"
  local payload
  payload=$(jq -n --arg query "$query" --argjson variables "$variables" '{query: $query, variables: $variables}')
  local response
  response=$(post_graphql "$WRITE_API" "$payload") || fail "the Write API request failed"
  if [ -z "$response" ] || ! echo "$response" | jq -e . >/dev/null 2>&1; then
    fail "the Write API returned an invalid response: $response"
  fi
  if [ "$(echo "$response" | jq -r 'has("errors")')" = "true" ]; then
    fail "the Write API returned errors: $response"
  fi
  echo "$response"
}

read_query() {
  local query="$1"
  local variables="$2"
  local payload
  payload=$(jq -n --arg query "$query" --argjson variables "$variables" '{query: $query, variables: $variables}')
  post_graphql "$READ_API" "$payload"
}

wait_for_ready() {
  local description="$1"
  local url="$2"
  local payload="$3"
  local filter="$4"
  local deadline
  deadline=$(( $(now) + TIMEOUT_SECONDS ))
  local response=""
  while [ "$(now)" -lt "$deadline" ]; do
    response=$(post_graphql "$url" "$payload" 2>/dev/null) || true
    if [ -n "$response" ] && echo "$response" | jq -e "$filter" >/dev/null 2>&1; then
      echo "OK: $description"
      return 0
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done
  echo "FAIL: timed out waiting for $description" >&2
  echo "last response: ${response}" >&2
  return 1
}

wait_for_read() {
  local description="$1"
  local filter="$2"
  local query="$3"
  local variables="$4"
  shift 4
  local deadline
  deadline=$(( $(now) + TIMEOUT_SECONDS ))
  local response=""
  while [ "$(now)" -lt "$deadline" ]; do
    response=$(read_query "$query" "$variables" 2>/dev/null) || true
    if [ -n "$response" ] && echo "$response" | jq -e "$@" "$filter" >/dev/null 2>&1; then
      echo "OK: $description"
      return 0
    fi
    sleep "$POLL_INTERVAL_SECONDS"
  done
  echo "FAIL: timed out waiting for $description" >&2
  echo "last response: ${response}" >&2
  return 1
}

echo "write api: ${WRITE_API}"
echo "read api: ${READ_API}"
echo "timeout: ${TIMEOUT_SECONDS}s"

wait_for_ready \
  "the Write API answers healthCheck" \
  "$WRITE_API" \
  '{"query":"{ healthCheck }"}' \
  '.data.healthCheck == "OK"' || exit 1

wait_for_ready \
  "the Read API answers a query" \
  "$READ_API" \
  '{"query":"{ __typename }"}' \
  '.data.__typename == "Query"' || exit 1

create_response=$(write_mutation \
  'mutation($input: CreateGroupChatInput!) { createGroupChat(input: $input) { groupChatId } }' \
  "$(jq -n --arg name "$GROUP_CHAT_NAME" --arg executor "$ADMIN_ID" '{input: {name: $name, executorId: $executor}}')")
GROUP_CHAT_ID=$(echo "$create_response" | jq -r '.data.createGroupChat.groupChatId')
[ "$GROUP_CHAT_ID" != "null" ] && [ -n "$GROUP_CHAT_ID" ] || fail "createGroupChat returned no id: $create_response"
echo "created group chat: ${GROUP_CHAT_ID}"

write_mutation \
  'mutation($input: AddMemberInput!) { addMember(input: $input) { groupChatId } }' \
  "$(jq -n --arg id "$GROUP_CHAT_ID" --arg member "$MEMBER_ID" --arg executor "$ADMIN_ID" \
    '{input: {groupChatId: $id, userAccountId: $member, role: "MEMBER", executorId: $executor}}')" \
  >/dev/null || exit 1
echo "added member: ${MEMBER_ID}"

post_response=$(write_mutation \
  'mutation($input: PostMessageInput!) { postMessage(input: $input) { groupChatId messageId } }' \
  "$(jq -n --arg id "$GROUP_CHAT_ID" --arg content "hello" --arg executor "$ADMIN_ID" \
    '{input: {groupChatId: $id, content: $content, executorId: $executor}}')")
MESSAGE_ID=$(echo "$post_response" | jq -r '.data.postMessage.messageId')
[ "$MESSAGE_ID" != "null" ] && [ -n "$MESSAGE_ID" ] || fail "postMessage returned no id: $post_response"
echo "posted message: ${MESSAGE_ID}"

wait_for_read \
  "the group chat reaches the read model" \
  '.data != null and .data.getGroupChats != null and (.data.getGroupChats | map(.id) | index($id) != null)' \
  'query($user: String!) { getGroupChats(userAccountId: $user) { id name } }' \
  "$(jq -n --arg user "$ADMIN_ID" '{user: $user}')" \
  --arg id "$GROUP_CHAT_ID" || exit 1

wait_for_read \
  "the member reaches the read model" \
  '.data != null and .data.getMembers != null and (.data.getMembers | map(.userAccountId) | index($member) != null)' \
  'query($id: String!, $user: String!) { getMembers(groupChatId: $id, userAccountId: $user) { id userAccountId role } }' \
  "$(jq -n --arg id "$GROUP_CHAT_ID" --arg user "$ADMIN_ID" '{id: $id, user: $user}')" \
  --arg member "$MEMBER_ID" || exit 1

wait_for_read \
  "the message reaches the read model" \
  '.data != null and .data.getMessages != null and (.data.getMessages | map(.id) | index($id) != null)' \
  'query($id: String!, $user: String!) { getMessages(groupChatId: $id, userAccountId: $user) { id text } }' \
  "$(jq -n --arg id "$GROUP_CHAT_ID" --arg user "$MEMBER_ID" '{id: $id, user: $user}')" \
  --arg id "$MESSAGE_ID" || exit 1

wait_for_read \
  "getMessage returns the posted text" \
  '.data != null and .data.getMessage != null and .data.getMessage.text == "hello"' \
  'query($id: String!, $user: String!) { getMessage(messageId: $id, userAccountId: $user) { id text } }' \
  "$(jq -n --arg id "$MESSAGE_ID" --arg user "$ADMIN_ID" '{id: $id, user: $user}')" || exit 1

write_mutation \
  'mutation($input: RenameGroupChatInput!) { renameGroupChat(input: $input) { groupChatId } }' \
  "$(jq -n --arg id "$GROUP_CHAT_ID" --arg name "renamed-team" --arg executor "$ADMIN_ID" \
    '{input: {groupChatId: $id, name: $name, executorId: $executor}}')" \
  >/dev/null || exit 1
wait_for_read \
  "renameGroupChat reaches the read model" \
  '.data != null and .data.getGroupChat != null and .data.getGroupChat.name == "renamed-team"' \
  'query($id: String!, $user: String!) { getGroupChat(groupChatId: $id, userAccountId: $user) { id name } }' \
  "$(jq -n --arg id "$GROUP_CHAT_ID" --arg user "$ADMIN_ID" '{id: $id, user: $user}')" || exit 1

write_mutation \
  'mutation($input: EditMessageInput!) { editMessage(input: $input) { groupChatId } }' \
  "$(jq -n --arg id "$GROUP_CHAT_ID" --arg message "$MESSAGE_ID" --arg content "hello-edited" --arg executor "$ADMIN_ID" \
    '{input: {groupChatId: $id, messageId: $message, content: $content, executorId: $executor}}')" \
  >/dev/null || exit 1
wait_for_read \
  "editMessage reaches the read model" \
  '.data != null and .data.getMessage != null and .data.getMessage.text == "hello-edited"' \
  'query($id: String!, $user: String!) { getMessage(messageId: $id, userAccountId: $user) { id text } }' \
  "$(jq -n --arg id "$MESSAGE_ID" --arg user "$ADMIN_ID" '{id: $id, user: $user}')" || exit 1

write_mutation \
  'mutation($input: DeleteMessageInput!) { deleteMessage(input: $input) { groupChatId } }' \
  "$(jq -n --arg id "$GROUP_CHAT_ID" --arg message "$MESSAGE_ID" --arg executor "$ADMIN_ID" \
    '{input: {groupChatId: $id, messageId: $message, executorId: $executor}}')" \
  >/dev/null || exit 1
wait_for_read \
  "deleteMessage removes the message from the read model" \
  '.data != null and .data.getMessages != null and (.data.getMessages | length == 0)' \
  'query($id: String!, $user: String!) { getMessages(groupChatId: $id, userAccountId: $user) { id } }' \
  "$(jq -n --arg id "$GROUP_CHAT_ID" --arg user "$ADMIN_ID" '{id: $id, user: $user}')" || exit 1

write_mutation \
  'mutation($input: RemoveMemberInput!) { removeMember(input: $input) { groupChatId } }' \
  "$(jq -n --arg id "$GROUP_CHAT_ID" --arg member "$MEMBER_ID" --arg executor "$ADMIN_ID" \
    '{input: {groupChatId: $id, userAccountId: $member, executorId: $executor}}')" \
  >/dev/null || exit 1
wait_for_read \
  "removeMember removes the member from the read model" \
  '.data != null and .data.getMembers != null and (.data.getMembers | length == 1) and (.data.getMembers[0].userAccountId != $member)' \
  'query($id: String!, $user: String!) { getMembers(groupChatId: $id, userAccountId: $user) { id userAccountId role } }' \
  "$(jq -n --arg id "$GROUP_CHAT_ID" --arg user "$ADMIN_ID" '{id: $id, user: $user}')" \
  --arg member "$MEMBER_ID" || exit 1

wait_for_read \
  "the removed member can no longer list the group chat" \
  '.data != null and .data.getGroupChats != null and (.data.getGroupChats | map(.id) | index($id) == null)' \
  'query($user: String!) { getGroupChats(userAccountId: $user) { id } }' \
  "$(jq -n --arg user "$MEMBER_ID" '{user: $user}')" \
  --arg id "$GROUP_CHAT_ID" || exit 1

write_mutation \
  'mutation($input: DeleteGroupChatInput!) { deleteGroupChat(input: $input) { groupChatId } }' \
  "$(jq -n --arg id "$GROUP_CHAT_ID" --arg executor "$ADMIN_ID" \
    '{input: {groupChatId: $id, executorId: $executor}}')" \
  >/dev/null || exit 1
wait_for_read \
  "deleteGroupChat removes the group chat from the read model" \
  '.data != null and .data.getGroupChats != null and (.data.getGroupChats | length == 0)' \
  'query($user: String!) { getGroupChats(userAccountId: $user) { id } }' \
  "$(jq -n --arg user "$ADMIN_ID" '{user: $user}')" || exit 1

echo "PASS: Write API -> head Streams -> RMU -> read model -> Read API"
