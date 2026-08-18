#!/usr/bin/env bash
# Run the DiagScope scan and expose report locations plus gate state to the workflow.
set -uo pipefail

DIAGSCOPE_HOME="${DIAGSCOPE_HOME:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
JAR="${DIAGSCOPE_JAR:-${DIAGSCOPE_HOME}/diagscope-cli/target/diagscope.jar}"
PROJECT="$(cd "${DIAGSCOPE_PROJECT:-.}" && pwd)"
OUTPUT="${DIAGSCOPE_OUTPUT:-target/diagscope}"

args=(scan --project "${PROJECT}" --output "${OUTPUT}"
      --format "${DIAGSCOPE_FORMATS:-MARKDOWN,JSON,HTML,SARIF}"
      --fail-on "${DIAGSCOPE_FAIL_ON:-NONE}")

[[ -n "${DIAGSCOPE_BASELINE:-}" ]] && args+=(--baseline "${DIAGSCOPE_BASELINE}")
[[ -n "${DIAGSCOPE_CHANGED_SINCE:-}" ]] && args+=(--changed-since "${DIAGSCOPE_CHANGED_SINCE}")
[[ -n "${DIAGSCOPE_CONFIG:-}" ]] && args+=(--config "${DIAGSCOPE_CONFIG}")
if [[ -n "${DIAGSCOPE_EXTRA_ARGS:-}" ]]; then
  # shellcheck disable=SC2206
  extra=(${DIAGSCOPE_EXTRA_ARGS})
  args+=("${extra[@]}")
fi

java -jar "${JAR}" "${args[@]}"
status=$?

if [[ "${OUTPUT}" = /* ]]; then
  report_dir="${OUTPUT}"
else
  report_dir="${PROJECT}/${OUTPUT}"
fi
sarif="${report_dir}/result.sarif"
[[ -f "${sarif}" ]] || sarif=""

findings=0
if [[ -f "${report_dir}/result.json" ]]; then
  findings="$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1])).get("findings", [])))' "${report_dir}/result.json" 2>/dev/null || echo 0)"
fi

gate_breached=false
[[ "${status}" -eq 1 ]] && gate_breached=true

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "report-directory=${report_dir}"
    echo "sarif-path=${sarif}"
    echo "findings=${findings}"
    echo "gate-breached=${gate_breached}"
  } >> "${GITHUB_OUTPUT}"
fi

if [[ -n "${GITHUB_STEP_SUMMARY:-}" && -f "${report_dir}/result.json" ]]; then
  python3 "${DIAGSCOPE_HOME}/scripts/action/render_summary.py" \
    "${report_dir}/result.json" >> "${GITHUB_STEP_SUMMARY}" || true
fi

# Status 1 is the severity gate; anything above it is a real scan failure.
exit "${status}"
