#!/usr/bin/env bash
# Brings up LocalStack, MySQL, the migrations, the DynamoDB setup and the three applications, then runs the e2e
# verification in its own container. The stack is torn down afterwards so a failed run keeps its logs but not its
# volumes.
set -eu

# shellcheck disable=SC2046
cd $(dirname "$0") || exit

PROJECT_NAME=cqrs-es-example-scala
COMPOSE_DATABASES="../docker-compose/docker-compose-databases.yml"
COMPOSE_APPLICATIONS="../docker-compose/docker-compose-applications.yml"
COMPOSE_E2E="../docker-compose/docker-compose-e2e-test.yml"

compose_full() {
  docker compose -p "${PROJECT_NAME}" \
    -f "${COMPOSE_DATABASES}" \
    -f "${COMPOSE_APPLICATIONS}" \
    -f "${COMPOSE_E2E}" \
    "$@"
}

compose_full down -v --remove-orphans >/dev/null 2>&1 || true

# The e2e file is part of `up` because it resets the host ports of the base files. The services are named so that the
# one-shot e2e services do not start here. Their dependencies start with them.
if ! compose_full up -d --build --remove-orphans "$@" \
  write-api-server-1 read-model-updater-1 read-api-server-1; then
  echo "the compose stack failed to start; dumping compose logs" >&2
  compose_full logs --no-color || true
  compose_full ps || true
  compose_full down -v --remove-orphans >/dev/null 2>&1 || true
  exit 1
fi

# A failed listing counts as a failure, so that the check is never skipped silently.
if ! containers=$(docker ps --filter "label=com.docker.compose.project=${PROJECT_NAME}" \
  --format '{{.Names}} {{.Ports}}') || printf '%s\n' "${containers}" | grep -e '->' >&2; then
  echo "the e2e stack must not publish host ports" >&2
  compose_full down -v --remove-orphans >/dev/null 2>&1 || true
  exit 1
fi

status=0
compose_full run --rm --build --no-deps e2e-test && status=0 || status=$?

if [ "${status}" -eq 0 ]; then
  # The fault-injection e2e runs its own read model updater. The long-running service must stop first, otherwise the
  # two readers race for the same head stream and the injected failures land in the wrong process. A failed stop is
  # fatal: starting the fault scenarios while the long-running updater is still running would make the result
  # meaningless.
  if ! compose_full stop -t 10 read-model-updater-1; then
    echo "the read model updater did not stop; skipping the fault-injection e2e" >&2
    status=1
  else
    compose_full run --rm --no-deps -T fault-e2e && status=0 || status=$?
  fi
fi

if [ "${status}" -ne 0 ]; then
  echo "e2e failed with status ${status}; dumping compose logs" >&2
  compose_full logs --no-color || true
  compose_full ps || true
fi

compose_full down -v --remove-orphans >/dev/null 2>&1 || true

exit "${status}"
