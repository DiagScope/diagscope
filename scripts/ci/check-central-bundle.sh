#!/usr/bin/env bash
# Checks a Central bundle for what the Portal rejects: every artifact needs a jar, a pom, sources, javadoc, and a
# signature and checksums for each file; the test fixtures must not be in it; every pom needs the metadata Central asks
# for. Usage: scripts/ci/check-central-bundle.sh <central-bundle.zip>
set -euo pipefail

bundle=${1:?path to central-bundle.zip}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
unzip -q "$bundle" -d "$work"
cd "$work"

fail=0
problem() { echo "PROBLEM: $*"; fail=1; }

modules=$(find . -mindepth 5 -maxdepth 5 -type d | sort)
[ -n "$modules" ] || { echo "the bundle has no artifacts"; exit 1; }

for dir in $modules; do
  name=$(basename "$(dirname "$dir")")
  version=$(basename "$dir")
  base="$dir/$name-$version"
  case "$name" in *test-fixtures*) problem "$name must not be published"; continue;; esac

  files=("$base.pom")
  if [ "$name" != "diagscope-parent" ]; then
    files+=("$base.jar" "$base-sources.jar" "$base-javadoc.jar")
  fi
  for file in "${files[@]}"; do
    [ -f "$file" ] || { problem "missing $file"; continue; }
    [ -f "$file.asc" ] || problem "missing signature for $file"
    for sum in md5 sha1; do
      [ -f "$file.$sum" ] || problem "missing $sum for $file"
    done
  done

  pom="$base.pom"
  for element in "<name>" "<description>" "<url>" "<licenses>" "<developers>" "<scm>"; do
    grep -q "$element" "$pom" || [ "$name" != "diagscope-parent" ] || problem "$pom lacks $element"
  done
  grep -q "<groupId>io.github.diagscope</groupId>" "$pom" || problem "$pom is not in the io.github.diagscope namespace"
  echo "ok: $name $version"
done

[ "$fail" -eq 0 ] || { echo "the bundle would be rejected"; exit 1; }
echo "the bundle looks complete"
