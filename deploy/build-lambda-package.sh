#!/usr/bin/env bash
#
# Builds the Lambda deployment package for the RMU, recovery worker and resync
# worker handlers. The package is a fat jar from sbt-assembly. Pass --upload to
# copy it to S3 after the build.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUTPUT=""
UPLOAD="false"
SKIP_BUILD="false"
BUCKET=""
KEY="rmu/rmu-lambda.jar"

usage() {
  cat <<'USAGE'
Usage: build-lambda-package.sh [options]

Options:
  --upload                Upload the jar to --bucket after building it.
  --bucket <name>         S3 bucket for --upload.
  --key <key>             S3 key. Defaults to rmu/rmu-lambda.jar.
  --output <path>         Copy the built jar to this path.
  --skip-build            Reuse an existing jar instead of running sbt.
  -h, --help              Show this help.

Build with JDK 17. The jar contains the three handler classes and every
dependency, which is the layout the AWS Lambda Java runtime accepts.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --upload)
      UPLOAD="true"
      shift
      ;;
    --bucket)
      BUCKET="$2"
      shift 2
      ;;
    --key)
      KEY="$2"
      shift 2
      ;;
    --output)
      OUTPUT="$2"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD="true"
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "$UPLOAD" == "true" && -z "$BUCKET" ]]; then
  echo "--bucket is required with --upload" >&2
  exit 2
fi

if [[ "$SKIP_BUILD" != "true" ]]; then
  (cd "$REPO_ROOT" && sbt -batch lambda/assembly)
fi

JAR="$(find "${REPO_ROOT}/modules/lambda/target" -name 'cqrs-es-example-scala-lambda.jar' -print -quit)"
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "lambda package not found under modules/lambda/target" >&2
  exit 2
fi

for handler in LambdaReadModelUpdater LambdaRecoveryWorker LambdaResyncWorker; do
  # Do not use grep -q here: it closes the pipe early, and pipefail then reports
  # the unzip SIGPIPE as a missing handler.
  if ! unzip -l "$JAR" | grep -F "lambda/${handler}.class" >/dev/null; then
    echo "lambda package does not contain ${handler}" >&2
    exit 2
  fi
done

if [[ -n "$OUTPUT" ]]; then
  mkdir -p "$(dirname "$OUTPUT")"
  cp "$JAR" "$OUTPUT"
  JAR="$OUTPUT"
fi

echo "lambda package: $JAR"
echo "size: $(wc -c <"$JAR" | tr -d ' ') bytes"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$JAR"
else
  shasum -a 256 "$JAR"
fi

if [[ "$UPLOAD" != "true" ]]; then
  echo "not uploaded: re-run with --upload --bucket <name> [--key <key>] to copy it to S3." >&2
  exit 0
fi

command -v aws >/dev/null 2>&1 || {
  echo "the AWS CLI is required for --upload" >&2
  exit 2
}

aws s3 cp "$JAR" "s3://${BUCKET}/${KEY}"
echo "uploaded: s3://${BUCKET}/${KEY}"
