#!/usr/bin/env python3
"""Prepares the working tree for a release: the POM versions, the changelog and the docs that quote the version.

Idempotent: when the POMs already carry the version, nothing changes. It refuses an empty changelog, because a release
with nothing to say is almost always a mistake.

Usage: scripts/release/prepare.py <version> [date]
"""
import datetime
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
PLAIN = re.compile(r"^\d+\.\d+\.\d+$")
VERSION = re.compile(r"^\d+\.\d+\.\d+(-(alpha|beta|rc)[.\d]*)?$")


def die(message):
    print(f"error: {message}", file=sys.stderr)
    sys.exit(2)


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def current_version():
    match = re.search(r"<version>([^<]+)</version>", read("pom.xml"))
    if not match:
        die("no project version in pom.xml")
    return match.group(1)


def key(version):
    return tuple(int(part) for part in version.split("-")[0].split("."))


def bump_poms(current, version):
    poms = ["pom.xml"] + sorted(str(p.relative_to(ROOT)) for p in ROOT.glob("diagscope-*/pom.xml"))
    old, new = f"<version>{current}</version>", f"<version>{version}</version>"
    for pom in poms:
        text = read(pom)
        if old not in text:
            die(f"{pom} does not carry {current}")
        write(pom, text.replace(old, new, 1))  # the first one is the project's, or its parent's, version
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    write("pom.xml", re.sub(r"(<project\.build\.outputTimestamp>)[^<]*", rf"\g<1>{stamp}", read("pom.xml")))
    return len(poms)


def update_changelog(version, date):
    text = read("CHANGELOG.md")
    match = re.search(r"## Unreleased\n(.*?)(?=\n## )", text, re.S)
    if not match or not match.group(1).strip():
        die("CHANGELOG.md has nothing under 'Unreleased'; write what this release changes first")
    if f"## {version} " in text:
        die(f"CHANGELOG.md already has a section for {version}")
    write("CHANGELOG.md", text.replace("## Unreleased\n", f"## Unreleased\n\n## {version} — {date}\n", 1))


def update_docs(current, version):
    """Only text that shows the *current* version; a sentence about a past version stays as it is."""
    edits = {
        "docs/CLI.md": [(rf"(?m)^diagscope {re.escape(current)}:", f"diagscope {version}:")],
        "docs/PERFORMANCE.md": [(rf"\({re.escape(current)}, cold JVM\)", f"({version}, cold JVM)")],
        "docs/MAVEN_PLUGIN.md": [
            (rf"<version>{re.escape(current)}</version>", f"<version>{version}</version>"),
            (rf"diagscope-maven-plugin:{re.escape(current)}:scan", f"diagscope-maven-plugin:{version}:scan"),
        ],
    }
    for path, rules in edits.items():
        text = read(path)
        for pattern, replacement in rules:
            text = re.sub(pattern, replacement, text)
        write(path, text)


def main():
    if len(sys.argv) < 2:
        die("usage: prepare.py <version> [date]")
    version = sys.argv[1]
    date = sys.argv[2] if len(sys.argv) > 2 else datetime.date.today().isoformat()
    if not VERSION.match(version):
        die(f"'{version}' is not a release version (X.Y.Z, optionally -alpha/-beta/-rc)")
    current = current_version()
    if current == version:
        print(f"already at {version}; nothing to prepare")
        return
    if PLAIN.match(current) and PLAIN.match(version) and key(version) <= key(current):
        die(f"{version} is not newer than {current}")
    update_changelog(version, date)
    count = bump_poms(current, version)
    update_docs(current, version)
    print(f"prepared {version} (was {current}): {count} POMs, the changelog and the docs")


if __name__ == "__main__":
    main()
