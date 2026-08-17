# Running DiagScope in CI

DiagScope is designed to run on every pull request: it is deterministic, needs no network access
and writes machine-readable output (`result.json`, `result.sarif`) next to the human report
(`report.html`, `report.md`).

## GitHub Action

The repository ships a composite action at the root (`action.yml`).

```yaml
name: DiagScope
on:
  pull_request:

permissions:
  contents: read
  pull-requests: write
  security-events: write

jobs:
  diagscope:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0            # required by --changed-since

      - uses: DiagScope/diagscope@main
        id: diagscope
        with:
          project: .
          fail-on: ERROR
          changed-since: origin/${{ github.base_ref }}
          baseline: diagscope-baseline.json

      - uses: github/codeql-action/upload-sarif@v3
        if: always() && steps.diagscope.outputs.sarif-path != ''
        with:
          sarif_file: ${{ steps.diagscope.outputs.sarif-path }}

      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: diagscope-report
          path: ${{ steps.diagscope.outputs.report-directory }}
```

### Inputs

| Input | Default | Purpose |
| --- | --- | --- |
| `project` | `.` | Maven or Gradle project directory |
| `output` | `target/diagscope` | Report directory |
| `formats` | `MARKDOWN,JSON,HTML,SARIF` | Report formats to produce |
| `fail-on` | `NONE` | Severity gate: `ERROR`, `WARNING`, `INFO`, `NONE` |
| `baseline` | *(empty)* | Baseline file used to hide already-known findings |
| `changed-since` | *(empty)* | Restrict findings to files changed since this revision |
| `config` | *(empty)* | Policy file; `diagscope.yml` is picked up automatically |
| `extra-args` | *(empty)* | Raw extra arguments for the `scan` command |
| `comment-on-pr` | `true` | Post or update one summary comment per pull request |
| `java-version` | `25` | JDK used to build and run DiagScope |

### Outputs

| Output | Description |
| --- | --- |
| `report-directory` | Absolute path of the generated reports |
| `sarif-path` | Absolute SARIF path, empty when SARIF was not requested |
| `findings` | Number of findings after baselines and waivers |
| `gate-breached` | `true` when the severity gate failed the scan |

The action writes an executive summary (findings by severity, confidence and rule) to the job
summary and mirrors it into a single, continuously updated pull-request comment.

## Gradle

Apply `gradle/diagscope.gradle` and run the generated task:

```bash
./gradlew diagscopeScan \
  -Pdiagscope.jar=tools/diagscope/diagscope.jar \
  -Pdiagscope.failOn=WARNING
```

Supported properties: `diagscope.jar`, `diagscope.output`, `diagscope.formats`,
`diagscope.failOn`, `diagscope.baseline`, `diagscope.changedSince`, `diagscope.config`.

## Any other CI

Every channel reuses the same engine (`ScanWorkflow`), so a plain jar invocation behaves
identically to the action:

```bash
mvn -q -pl diagscope-cli -am -DskipTests package
java -jar diagscope-cli/target/diagscope.jar scan \
  --project . \
  --format JSON,SARIF \
  --fail-on ERROR \
  --baseline diagscope-baseline.json
```

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Scan completed, gate not breached |
| `1` | Findings at or above `--fail-on` |
| `2` | Invalid configuration or unexpected failure |
| `3` | Unsupported project layout |

## Adoption recipe

1. Start with `fail-on: NONE` and publish reports as artifacts.
2. Record the current state: `scan --update-baseline`, commit `diagscope-baseline.json`.
3. Switch to `fail-on: ERROR` with `changed-since` so only new code is gated.
4. Tighten the gate as the baseline shrinks; waive intentional cases in `diagscope.yml`.

## Embedding the engine

`ScanWorkflow` is the public entry point for plugins and custom wrappers:

```java
var workflow = DiagScopeMain.createScanWorkflow();
var outcome = workflow.run(new ScanWorkflow.Request(
        projectDir, Path.of("target/diagscope"), 3, 0,
        EnumSet.of(ReportFormat.JSON, ReportFormat.SARIF), FailOn.ERROR,
        null, false, List.of(), false, null, null, List.of(), List.of(), null));
if (outcome.gateBreached()) {
    throw new IllegalStateException("DiagScope gate failed: " + outcome.outputDirectory());
}
```
