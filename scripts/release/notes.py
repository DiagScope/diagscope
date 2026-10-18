#!/usr/bin/env python3
"""Prints the changelog section of a version, to use as its release notes.

Usage: scripts/release/notes.py <version>
"""
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parents[2]
version = sys.argv[1]
text = (root / "CHANGELOG.md").read_text(encoding="utf-8")
match = re.search(rf"## {re.escape(version)} [^\n]*\n(.*?)(?=\n## |\Z)", text, re.S)
if not match or not match.group(1).strip():
    sys.exit(f"CHANGELOG.md has no section for {version}")
print(match.group(1).strip())
