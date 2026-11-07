#!/usr/bin/env bash
set -eu

# shellcheck disable=SC2046
cd $(dirname "$0") || exit

PROJECT_NAME=cqrs-es-example-scala
COMPOSE_DATABASES="../docker-compose/docker-compose-databases.yml"
COMPOSE_APPLICATIONS="../docker-compose/docker-compose-applications.yml"

docker compose -p "${PROJECT_NAME}" \
  -f "${COMPOSE_DATABASES}" \
  -f "${COMPOSE_APPLICATIONS}" \
  up -d --build "$@"

docker compose -p "${PROJECT_NAME}" \
  -f "${COMPOSE_DATABASES}" \
  -f "${COMPOSE_APPLICATIONS}" \
  ps
