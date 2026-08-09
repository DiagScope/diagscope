#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 1 ]]; then echo "Usage: $0 /path/to/jvm-project [scan options...]" >&2; exit 1; fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/diagscope-cli/target/diagscope.jar"
PROJECT="$(cd "$1" && pwd)"
shift
OUTPUT="$ROOT/target/profile"
mkdir -p "$OUTPUT"

if [[ ! -f "$JAR" ]]; then
  mvn -q -f "$ROOT/pom.xml" -DskipTests package
fi

java \
  -XX:StartFlightRecording=filename="$OUTPUT/diagscope.jfr",settings=profile,dumponexit=true \
  -jar "$JAR" scan --project "$PROJECT" --output "$OUTPUT/report" "$@"

echo "JFR written to $OUTPUT/diagscope.jfr"
if command -v jfr >/dev/null 2>&1; then
  jfr print --events dev.diagscope.KotlinPsiAnalysis --json "$OUTPUT/diagscope.jfr" \
    > "$OUTPUT/kotlin-psi-events.json"
  echo "Kotlin PSI events written to $OUTPUT/kotlin-psi-events.json"
fi
