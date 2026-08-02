#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 1 ]]; then echo "Usage: $0 /path/to/maven-project" >&2; exit 1; fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/diagscope-cli/target/diagscope.jar"
PROJECT="$(cd "$1" && pwd)"
OUTPUT="$ROOT/target/profile"
mkdir -p "$OUTPUT"

if [[ ! -f "$JAR" ]]; then
  mvn -q -f "$ROOT/pom.xml" -DskipTests package
fi

java \
  -XX:StartFlightRecording=filename="$OUTPUT/diagscope.jfr",settings=profile,dumponexit=true \
  -jar "$JAR" scan --project "$PROJECT" --output "$OUTPUT/report"

echo "JFR written to $OUTPUT/diagscope.jfr"
