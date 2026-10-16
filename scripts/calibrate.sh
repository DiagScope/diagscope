#!/usr/bin/env bash
# Scans a set of open-source projects with the built jar and prints findings per rule, to catch false positives
# before a release. Usage: scripts/calibrate.sh <work-dir> [jar]   (run `mvn package` first)
set -euo pipefail
work=${1:?work directory}
jar=${2:-diagscope-cli/target/diagscope.jar}
projects=(
  spring-projects/spring-petclinic
  spring-petclinic/spring-petclinic-kotlin
  spring-petclinic/spring-petclinic-microservices
  jhipster/jhipster-sample-app
  quarkusio/quarkus-quickstarts
  vert-x3/vertx-examples
  spring-projects/spring-data-examples
)
mkdir -p "$work/src" "$work/out"
for repo in "${projects[@]}"; do
  name=${repo#*/}
  [ -d "$work/src/$name" ] || git clone -q --depth 1 "https://github.com/$repo.git" "$work/src/$name"
  java -jar "$jar" scan --project "$work/src/$name" --output "$work/out/$name" --format JSON >/dev/null
  python3 - "$work/out/$name/result.json" "$name" <<'PY'
import collections, json, sys
findings = json.load(open(sys.argv[1]))["findings"]
counts = collections.Counter(item["ruleId"] for item in findings)
print(f"\n{sys.argv[2]}: {len(findings)} findings")
for rule, count in counts.most_common(8):
    print(f"  {count:5} {rule}")
PY
done
