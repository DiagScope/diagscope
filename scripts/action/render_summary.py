#!/usr/bin/env python3
"""Render a DiagScope result.json as a compact Markdown executive summary.

Used by the GitHub Action for the job summary and the pull-request comment, so the
output must stay readable in both places and never fail the job on malformed input.
"""
from __future__ import annotations

import json
import sys
from collections import Counter

SEVERITY_ORDER = ["ERROR", "WARNING", "INFO"]
CONFIDENCE_ORDER = ["HIGH", "MEDIUM", "MED", "LOW"]


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: render_summary.py <result.json>", file=sys.stderr)
        return 2
    try:
        with open(sys.argv[1], encoding="utf-8") as handle:
            result = json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        print(f"DiagScope summary unavailable: {error}", file=sys.stderr)
        return 0

    findings = result.get("findings", []) or []
    statistics = result.get("statistics", {}) or {}

    by_severity = Counter(str(f.get("severity", "INFO")).upper() for f in findings)
    by_confidence = Counter(str(f.get("confidence", "LOW")).upper() for f in findings)
    by_rule = Counter(str(f.get("ruleId", f.get("rule", "UNKNOWN"))) for f in findings)

    lines = ["## DiagScope", ""]
    if not findings:
        lines.append("No diagnostic coverage gaps found.")
    else:
        severities = " | ".join(
            f"**{name.title()}** {by_severity.get(name, 0)}" for name in SEVERITY_ORDER
        )
        confidences = " | ".join(
            f"{name.title()} {by_confidence[name]}"
            for name in CONFIDENCE_ORDER
            if by_confidence.get(name)
        )
        lines.append(f"{len(findings)} finding(s) — {severities}")
        if confidences:
            lines.append("")
            lines.append(f"Confidence: {confidences}")
        lines += ["", "| Rule | Findings |", "| --- | ---: |"]
        for rule, count in by_rule.most_common(15):
            lines.append(f"| `{rule}` | {count} |")
        if len(by_rule) > 15:
            lines.append(f"| _{len(by_rule) - 15} more rule(s)_ | |")

    files = statistics.get("sourceFiles")
    methods = statistics.get("parsedMethods")
    flows = statistics.get("flows")
    if files is not None:
        lines += ["", f"Analyzed {files} file(s), {methods} method(s), {flows} flow(s)."]

    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
