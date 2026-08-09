# Validation and differential-value matrix

This is the evidence contract for the remaining Step 3 gates. It is deliberately a protocol, not a
claim: no performance or differential-value box is checked until results from the same real
repository revision are attached and reviewed with its maintainers.

## Corpus record

For each repository record: repository/revision, build system, Java/Kotlin file and approximate LOC
counts, relevant frameworks, hardware, OS, JDK, JVM options, DiagScope version, scan options,
classpath/source-root overrides, and whether the filesystem cache is cold or warm.

Run the checked-in harness after packaging DiagScope:

```bash
./scripts/validate-corpus.sh /path/to/project team-repository-revision 5 -- \
  --classpath /already/built/dependency.jar \
  --source-root generated/sources
```

The harness never runs the target repository build. Every dependency and dynamic source root must be
declared explicitly. It writes `target/validation/<record-id>/` with environment metadata, per-run
wall/resource output (including peak RSS where the host permits it), normalized result digests,
unresolved Kotlin boundary candidates, and a JFR. Runs fail if normalized semantic JSON differs.
Cache state and repository facts still require an operator record because scripts cannot safely
flush a host cache or infer business ownership.

## Evidence registry

| Record ID | Repository/revision | Cache | Kotlin files/LOC | Warm runs | Cold runs | Semantic digest stable | Maintainer review | Status |
|---|---|---|---:|---:|---:|---|---|---|
| pending | pending | pending | pending | 0 | 0 | pending | pending | awaiting adopted corpus |

Run at least five warm scans and two cold scans. Preserve normalized `result.json`, its digest, wall
time, peak RSS/heap, and a JFR recording. Repeated scans must have identical findings, flows,
boundaries, confidence, and fingerprints after timing fields are normalized.

## Kotlin PSI parallelism gate

Do not parallelize Kotlin PSI merely because Java parsing is parallel. Introduce an experiment only
when all of these are true on at least two Kotlin-heavy corpus repositories:

- Kotlin parsing is at least 35% of project-analysis CPU and has p95 above two seconds;
- JFR attributes the cost to independent PSI parsing/mapping rather than shared environment setup,
  symbol resolution, filesystem access, or report generation;
- a bounded prototype improves median and p95 by at least 15% without increasing peak memory by
  more than 20%;
- normalized semantic output is byte-for-byte equivalent across repeated worker counts.

Record the rejected experiment as well as an accepted one; “sequential is cheaper” is a valid result.
The JFR recording includes `dev.diagscope.KotlinPsiAnalysis`, whose duration and source-file count
is the authoritative adapter interval. CPU samples inside that interval distinguish PSI/map work
from environment setup and dependency resolution.

## Kotlin dependency-classpath gate

Each validation run writes `kotlin-resolution-candidates.tsv` from Kotlin `AMBIGUOUS`, `EXTERNAL`,
and `UNRESOLVED` flow boundaries. A row is only a compiler-grade resolver candidate when all of the
following are recorded:

- the boundary changes a reachable flow, finding, severity, or confidence decision rather than only
  improving display metadata;
- callable shape, source-declared hierarchy, receiver typing, and cross-language relinking cannot
  select a unique declaration;
- the missing fact exists in a caller-declared JAR/classes entry and can be resolved without running
  Maven or Gradle;
- at least two real scans reproduce the edge, or one scan demonstrates a high-impact false positive
  confirmed by maintainers.

Until such a row exists, Kotlin remains source-first even when `--classpath` is supplied; the option
is still recorded so the evidence is auditable.

## IDE and linter comparison

Run the inspections already standard for each validation team (for example IntelliJ inspections,
SonarLint/SonarQube, SpotBugs, Checkstyle, Error Prone, or Detekt) with their committed configuration.
Do not enable a new rule set solely to make either tool look better.

For every DiagScope finding, record one row:

| Repository/revision | DiagScope rule | Method/flow | Maintainer verdict | IDE/linter equivalent | Differential value |
|---|---|---|---|---|---|
| pending | pending | pending | valid/noise/already-covered | tool + rule or none | new defect / stronger flow context / duplicate |

Copy `docs/validation/INSPECTION_COMPARISON.csv` into the record directory and fill it with the
maintainers. Keep the original IDE/linter export beside it; the CSV is the normalized verdict, not a
replacement for source evidence.

Also record linter findings in the same source area that DiagScope missed. A “flow-context
differential” counts only when maintainers say the entrypoint path, confidence, or incident evidence
changes prioritization; a differently worded duplicate does not.

The comparison succeeds only with the existing product gate: at least ten reviewable findings,
80% validity, no more than 20% noise, three previously unnoticed valid issues, deterministic output,
and explicit evidence that the standard inspection set did not already provide equivalent guidance.
