#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 1 ]]; then echo "Usage: $0 /path/to/project [iterations]" >&2; exit 1; fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT/diagscope-cli/target/diagscope.jar"
PROJECT="$(cd "$1" && pwd)"
ITERATIONS="${2:-5}"

if [[ ! -f "$JAR" ]]; then
  mvn -q -f "$ROOT/pom.xml" -DskipTests package
fi

for ((i=1; i<=ITERATIONS; i++)); do
  echo "iteration=$i"
  /usr/bin/time -p \
    java -jar "$JAR" scan --project "$PROJECT" --output "$ROOT/target/benchmark/$i" >/dev/null
done
