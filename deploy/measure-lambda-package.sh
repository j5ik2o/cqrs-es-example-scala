#!/usr/bin/env bash
#
# Measures the Lambda deployment package without changing it. The package is a
# shaded fat jar from sbt-assembly, so it has no lib/ directory: the script
# reports the jar size, the top entries, and the runtime dependency jars that a
# lib/ layout would contain. It never fails on size; there is no threshold.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAR=""
TOP="20"
READ_CLASSPATH="true"

usage() {
  cat <<'USAGE'
Usage: measure-lambda-package.sh [options]

Options:
  --jar <path>       Jar to measure. Defaults to the jar under modules/lambda/target.
  --top <n>          Number of entries to list. Defaults to 20.
  --no-classpath     Skip reading the sbt runtime classpath, so no dependency jar list is printed.
  -h, --help         Show this help.

Build the package first with `sbt lambda/assembly` or
`deploy/build-lambda-package.sh`. This script only measures an existing jar.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --jar)
      JAR="$2"
      shift 2
      ;;
    --top)
      TOP="$2"
      shift 2
      ;;
    --no-classpath)
      READ_CLASSPATH="false"
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

if [[ -z "$JAR" ]]; then
  JAR="$(find "${REPO_ROOT}/modules/lambda/target" -name 'cqrs-es-example-scala-lambda.jar' -print -quit)"
fi
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "lambda package not found; run sbt lambda/assembly or deploy/build-lambda-package.sh first" >&2
  exit 2
fi

command -v unzip >/dev/null 2>&1 || {
  echo "unzip is required" >&2
  exit 2
}

bytes_to_mib() {
  awk -v bytes="$1" 'BEGIN { printf "%.2f", bytes / 1048576 }'
}

COMPRESSED_BYTES="$(wc -c <"$JAR" | tr -d ' ')"
UNCOMPRESSED_BYTES="$(unzip -l "$JAR" | awk 'NR > 3 && $1 ~ /^[0-9]+$/ && $4 != "" { total += $1 } END { print total + 0 }')"
ENTRY_COUNT="$(unzip -Z1 "$JAR" | wc -l | tr -d ' ')"

echo "== Lambda package =="
echo "path: $JAR"
echo "compressed bytes: $COMPRESSED_BYTES ($(bytes_to_mib "$COMPRESSED_BYTES") MiB)"
echo "uncompressed bytes: $UNCOMPRESSED_BYTES ($(bytes_to_mib "$UNCOMPRESSED_BYTES") MiB)"
echo "entries: $ENTRY_COUNT"

echo
echo "== Largest entries in the package (top ${TOP}, uncompressed) =="
unzip -l "$JAR" | awk 'NR > 3 && $1 ~ /^[0-9]+$/ && $4 != "" { print $1 "\t" $4 }' |
  sort -rn | awk -v limit="$TOP" -F '\t' 'NR <= limit { printf "%12d  %8.2f MiB  %s\n", $1, $1 / 1048576, $2 }'

if [[ "$READ_CLASSPATH" == "true" ]]; then
  # sbt writes its log lines around the exported classpath, so pick the last line that is an absolute path and
  # contains a jar. LAMBDA_RUNTIME_CLASSPATH lets a caller supply the classpath without invoking sbt.
  if [[ -z "${LAMBDA_RUNTIME_CLASSPATH:-}" ]]; then
    LAMBDA_RUNTIME_CLASSPATH="$(cd "$REPO_ROOT" && sbt -batch -Dsbt.log.noformat=true \
      "export lambda/Runtime/fullClasspath" 2>/dev/null | grep -E '^/.*\.jar' | tail -n 1 || true)"
  fi

  if [[ -z "$LAMBDA_RUNTIME_CLASSPATH" ]]; then
    echo
    echo "warning: could not read the runtime classpath from sbt; skipping the dependency list" >&2
  else
    echo
    echo "== Runtime dependency jars =="
    JAR_COUNT=0
    JAR_TOTAL=0
    JAR_LIST="$(mktemp)"
    trap 'rm -f "$JAR_LIST"' EXIT

    while IFS= read -r entry; do
      if [[ "$entry" == *.jar && -f "$entry" ]]; then
        size="$(wc -c <"$entry" | tr -d ' ')"
        printf '%s\t%s\n' "$size" "$(basename "$entry")" >>"$JAR_LIST"
        JAR_COUNT=$((JAR_COUNT + 1))
        JAR_TOTAL=$((JAR_TOTAL + size))
      fi
    done < <(printf '%s' "$LAMBDA_RUNTIME_CLASSPATH" | tr ':' '\n')

    echo "lib/ is not present: the package is a shaded fat jar. Listing the runtime dependency jars that a lib/"
    echo "layout would contain instead."
    echo "jars: ${JAR_COUNT}, total jar bytes: ${JAR_TOTAL} ($(bytes_to_mib "$JAR_TOTAL") MiB)"
    if [[ -s "$JAR_LIST" ]]; then
      sort -rn "$JAR_LIST" |
        awk -v limit="$TOP" -F '\t' 'NR <= limit { printf "%12d  %8.2f MiB  %s\n", $1, $1 / 1048576, $2 }'
    fi

    echo
    echo "== ESA backend dependencies (Spanner / Bigtable / SQLite) =="
    for backend in spanner bigtable sqlite; do
      matches="$(grep -i "$backend" "$JAR_LIST" | awk -F '\t' '{ print $2 }' | sort || true)"
      if [[ -n "$matches" ]]; then
        echo "${backend}: present"
        printf '%s\n' "$matches" | sed 's/^/  /'
      else
        echo "${backend}: absent from the runtime classpath"
      fi
    done
  fi
fi
