# Validation and differential-value matrix

This is the evidence contract for the remaining Step 3 gates. It is deliberately a protocol, not a
claim: no performance or differential-value box is checked until results from the same real
repository revision are attached and reviewed with its maintainers.

## Corpus record

For each repository record: repository/revision, build system, Java/Kotlin file and approximate LOC
counts, relevant frameworks, hardware, OS, JDK, JVM options, DiagScope version, scan options,
classpath/source-root overrides, and whether the filesystem cache is cold or warm.

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

## IDE and linter comparison

Run the inspections already standard for each validation team (for example IntelliJ inspections,
SonarLint/SonarQube, SpotBugs, Checkstyle, Error Prone, or Detekt) with their committed configuration.
Do not enable a new rule set solely to make either tool look better.

For every DiagScope finding, record one row:

| Repository/revision | DiagScope rule | Method/flow | Maintainer verdict | IDE/linter equivalent | Differential value |
|---|---|---|---|---|---|
| pending | pending | pending | valid/noise/already-covered | tool + rule or none | new defect / stronger flow context / duplicate |

Also record linter findings in the same source area that DiagScope missed. A “flow-context
differential” counts only when maintainers say the entrypoint path, confidence, or incident evidence
changes prioritization; a differently worded duplicate does not.

The comparison succeeds only with the existing product gate: at least ten reviewable findings,
80% validity, no more than 20% noise, three previously unnoticed valid issues, deterministic output,
and explicit evidence that the standard inspection set did not already provide equivalent guidance.
