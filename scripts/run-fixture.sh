#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURE="$ROOT/diagscope-test-fixtures/src/main/resources/fixtures/mixed-flow"
JAR="$ROOT/diagscope-cli/target/diagscope.jar"

if [[ ! -f "$JAR" ]]; then
  mvn -q -f "$ROOT/pom.xml" -DskipTests package
fi

java -jar "$JAR" scan --project "$FIXTURE" --output "$ROOT/target/fixture-report"
