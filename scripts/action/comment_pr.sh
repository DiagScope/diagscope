#!/usr/bin/env bash
# Post or update a single DiagScope summary comment on the current pull request.
set -euo pipefail

REPORT_DIR="${DIAGSCOPE_REPORT_DIR:-}"
RESULT="${REPORT_DIR}/result.json"
MARKER="<!-- diagscope-report -->"
HOME_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ -z "${REPORT_DIR}" || ! -f "${RESULT}" ]]; then
  echo "No DiagScope result.json to comment on; skipping."
  exit 0
fi
if [[ -z "${GITHUB_EVENT_PATH:-}" || ! -f "${GITHUB_EVENT_PATH}" ]]; then
  echo "No pull-request event payload; skipping."
  exit 0
fi

PR_NUMBER="$(python3 -c 'import json,os;print(json.load(open(os.environ["GITHUB_EVENT_PATH"])).get("pull_request",{}).get("number",""))')"
if [[ -z "${PR_NUMBER}" ]]; then
  echo "Not a pull request; skipping."
  exit 0
fi

BODY_FILE="$(mktemp)"
{
  echo "${MARKER}"
  python3 "${HOME_DIR}/scripts/action/render_summary.py" "${RESULT}"
  echo
  echo "_Full HTML report is available in the workflow artifacts._"
} > "${BODY_FILE}"

REPO="${GITHUB_REPOSITORY}"
EXISTING="$(gh api "repos/${REPO}/issues/${PR_NUMBER}/comments" --paginate \
  --jq "map(select(.body | startswith(\"${MARKER}\"))) | .[0].id // empty" || true)"

if [[ -n "${EXISTING}" ]]; then
  gh api -X PATCH "repos/${REPO}/issues/comments/${EXISTING}" -F body=@"${BODY_FILE}" >/dev/null
  echo "Updated DiagScope comment ${EXISTING}."
else
  gh api -X POST "repos/${REPO}/issues/${PR_NUMBER}/comments" -F body=@"${BODY_FILE}" >/dev/null
  echo "Created DiagScope comment on PR #${PR_NUMBER}."
fi
rm -f "${BODY_FILE}"
