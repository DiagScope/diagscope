#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 /path/to/project record-id [iterations] [-- scan options...]" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/diagscope-cli/target/diagscope.jar"
PROJECT="$(cd "$1" && pwd)"
RECORD_ID="$2"
shift 2
ITERATIONS=5
if [[ $# -gt 0 && "$1" != "--" ]]; then ITERATIONS="$1"; shift; fi
if [[ $# -gt 0 && "$1" == "--" ]]; then shift; fi
OUTPUT="$ROOT/target/validation/$RECORD_ID"

if ! [[ "$RECORD_ID" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "record-id may contain only letters, numbers, dot, underscore, and dash" >&2
  exit 2
fi
if ! [[ "$ITERATIONS" =~ ^[1-9][0-9]*$ ]]; then
  echo "iterations must be a positive integer" >&2
  exit 2
fi
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to normalize and compare result.json files" >&2
  exit 2
fi
if [[ ! -f "$JAR" ]]; then
  mvn -q -f "$ROOT/pom.xml" -DskipTests package
fi

mkdir -p "$OUTPUT"
{
  echo "recordId=$RECORD_ID"
  echo "project=$PROJECT"
  echo "revision=$(git -C "$PROJECT" rev-parse HEAD 2>/dev/null || echo unavailable)"
  echo "os=$(uname -a)"
  echo "jdk=$(java -version 2>&1 | head -n 1)"
  echo "processors=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo unavailable)"
  echo "diagscope=$(java -jar "$JAR" --version)"
  printf 'scanOptions='
  if [[ $# -gt 0 ]]; then printf '%q ' "$@"; fi
  echo
  echo "cacheState=operator-declared-in-VALIDATION_MATRIX.md"
} > "$OUTPUT/environment.txt"

REFERENCE=""
for ((iteration=1; iteration<=ITERATIONS; iteration++)); do
  RUN="$OUTPUT/run-$iteration"
  mkdir -p "$RUN"
  TIME_OPTIONS=(-p)
  if [[ "$(uname -s)" == "Darwin" ]]; then TIME_OPTIONS=(-lp); else TIME_OPTIONS=(-vp); fi
  set +e
  {
    /usr/bin/time "${TIME_OPTIONS[@]}" java -jar "$JAR" scan \
      --project "$PROJECT" --output "$RUN/report" --format JSON "$@"
  } > "$RUN/stdout.log" 2> "$RUN/time.log"
  RUN_STATUS=$?
  set -e
  if [[ ! -f "$RUN/report/result.json" ]]; then
    echo "Validation scan $iteration failed with status $RUN_STATUS; see $RUN" >&2
    exit 3
  fi
  jq 'del(.statistics.projectAnalysisNanos, .statistics.flowConstructionNanos,
          .statistics.ruleExecutionNanos, .statistics.totalNanos)' \
    "$RUN/report/result.json" > "$RUN/result.normalized.json"
  shasum -a 256 "$RUN/result.normalized.json" > "$RUN/result.normalized.sha256"
  jq -r '.flows[].boundaries[]
    | select((.location.file | endswith(".kt")) and
             (.resolutionReason == "AMBIGUOUS" or .resolutionReason == "EXTERNAL" or
              .resolutionReason == "UNRESOLVED"))
    | [.resolutionReason, .location.file, .location.startLine, .call] | @tsv' \
    "$RUN/report/result.json" > "$RUN/kotlin-resolution-candidates.tsv"
  if [[ -z "$REFERENCE" ]]; then
    REFERENCE="$RUN/result.normalized.json"
  elif ! cmp -s "$REFERENCE" "$RUN/result.normalized.json"; then
    echo "Semantic output changed between validation runs; see $RUN" >&2
    exit 3
  fi
done

"$ROOT/scripts/profile-jfr.sh" "$PROJECT" --format JSON "$@"
cp "$ROOT/target/profile/diagscope.jfr" "$OUTPUT/diagscope.jfr"
if [[ -f "$ROOT/target/profile/kotlin-psi-events.json" ]]; then
  cp "$ROOT/target/profile/kotlin-psi-events.json" "$OUTPUT/kotlin-psi-events.json"
fi

echo "Validation record written to $OUTPUT"
echo "Record cache state, repository facts, and review verdicts in docs/VALIDATION_MATRIX.md."
