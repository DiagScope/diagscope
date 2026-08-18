#!/usr/bin/env bash
# Build the shaded DiagScope CLI once per job, unless a prebuilt jar is provided.
set -euo pipefail

DIAGSCOPE_HOME="${DIAGSCOPE_HOME:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
JAR="${DIAGSCOPE_JAR:-${DIAGSCOPE_HOME}/diagscope-cli/target/diagscope.jar}"

if [[ -f "${JAR}" ]]; then
  echo "Reusing DiagScope CLI at ${JAR}"
  exit 0
fi

MVN="${DIAGSCOPE_HOME}/mvnw"
if [[ ! -x "${MVN}" ]]; then
  MVN="mvn"
fi

echo "Building DiagScope CLI from ${DIAGSCOPE_HOME}"
"${MVN}" -q -B -f "${DIAGSCOPE_HOME}/pom.xml" -pl diagscope-cli -am -DskipTests package
test -f "${JAR}" || { echo "DiagScope CLI jar was not produced at ${JAR}" >&2; exit 1; }
