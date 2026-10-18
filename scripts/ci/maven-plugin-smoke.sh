#!/usr/bin/env bash
# Runs the installed Maven plugin against a bundled sample project, with whatever JDK JAVA_HOME names, and checks that the
# gate decides the build: a plain scan passes, a gate on the sample's known ERROR findings fails it.
# Usage: JAVA_HOME=/path/to/jdk scripts/ci/maven-plugin-smoke.sh   (run `mvn install -DskipTests` first)
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
version=$(mvn -q -B help:evaluate -Dexpression=project.version -DforceStdout -f "$root/pom.xml")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
cp -r "$root/diagscope-test-fixtures/src/main/resources/fixtures/mixed-flow" "$work/sample"
cd "$work/sample"

echo "Maven on: $(mvn -B -version | grep 'Java version')"
goal="io.github.diagscope:diagscope-maven-plugin:${version}:scan"

mvn -B -q "$goal"
[ -f target/diagscope/result.json ] || { echo "no report was written"; exit 1; }
echo "plain scan: ok"

if output=$(mvn -B -q "$goal" -Ddiagscope.failOn=ERROR 2>&1); then
  echo "the gate did not fail the build"
  exit 1
fi
grep -q "DiagScope gate failed" <<<"$output" || { echo "$output"; echo "the build failed for another reason"; exit 1; }
echo "gate on ERROR fails the build: ok"
