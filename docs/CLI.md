# CLI reference

## Version

```bash
java -jar diagscope-cli/target/diagscope.jar --version
```

The Maven artifact uses `-SNAPSHOT` while `0.1.0-alpha.1` is under validation.

## Scan command

```bash
java -jar diagscope-cli/target/diagscope.jar scan \
  --project /path/to/project \
  --output target/diagscope \
  --max-depth 3 \
  --parallelism 8 \
  --entrypoint REST,KAFKA_LISTENER,SCHEDULED \
  --format MARKDOWN,JSON \
  --config config/diagscope-team.yml \
  --baseline \
  --changed-since origin/main \
  --fail-on WARNING
```

## Options

- `-p`, `--project` — required directory for a conventional Maven or Gradle JVM project;
- `-o`, `--output` — output directory, default `target/diagscope`; a relative path is resolved below the analyzed project and cannot escape it with `..`; an absolute path is used explicitly as supplied;
- `--max-depth` — maximum local call depth, from `0` through `32`;
- `--parallelism` — Java parser worker count; `0` selects the automatic bounded policy (Kotlin PSI is sequential in this increment);
- `--entrypoint` — comma-separated subset of `REST`, `KAFKA_LISTENER`, and `SCHEDULED`;
- `--format` — comma-separated `MARKDOWN`, `JSON`, `HTML`, and/or `SARIF`;
- `--fail-on` — optional severity gate (`ERROR`, `WARNING`, `INFO`, or `NONE`);
- `--baseline [path]` — suppress findings whose stable fingerprint is in the selected baseline; when
  the option has no path, `<project>/diagscope-baseline.json` is used;
- `--update-baseline` — atomically rewrite the selected baseline, or the default baseline when
  `--baseline` is absent, using every finding from the full scan;
- `--baseline-migration <OLD=NEW>` — while updating, record an intentional stable-fingerprint
  migration from an old baseline entry to a current finding; repeat for multiple migrations;
- `--prune-removed-baseline` — while updating, discard removed-finding tombstones after their
  lifecycle has been reviewed;
- `--changed-since <ref>` — retain findings only when their source file appears in `git diff <ref>`.
- `--config <path>` — load a strict project policy; without this option, `diagscope.yml` is loaded
  automatically from the project root when present.
- `--source-root <path>` — add an existing production source directory inside the project; repeat
  the option or comma-separate entries for dynamic/generated roots;
- `--classpath <path>` — opt into Java dependency symbol solving with an existing JAR or classes
  directory; repeat the option or comma-separate entries. No build command is executed.

The automatic worker policy may also be selected with the `diagscope.parallelism` system property. Explicit command options take precedence.

Relative output and baseline paths must stay inside the analyzed project; explicit absolute paths
are accepted. `--changed-since` requires the project to be inside a Git working tree and rejects an
unknown or malformed revision.

Configuration precedence and the complete YAML schema are documented in
[CONFIGURATION.md](CONFIGURATION.md).

## Baseline workflow

Create or refresh the default baseline:

```bash
java -jar diagscope.jar scan --project . --update-baseline
```

Gate only findings not present in that baseline:

```bash
java -jar diagscope.jar scan --project . --baseline --fail-on WARNING
```

A custom file is selected with `--baseline config/accepted-findings.json`; combining it with
`--update-baseline` updates that file. Baselines are JSON objects keyed by SHA-256 finding
fingerprint, carry both a baseline schema version and the finding `fingerprintVersion`, omit
timestamps, and are written atomically. An incompatible fingerprint version is rejected instead of
silently suppressing the wrong findings.

Baseline schema `1.1` retains findings that disappeared from a scan under `removedFindings`, with
`status: REMOVED`. This distinguishes a real fix/removal from accidental baseline churn. When a
fingerprint changes intentionally, migrate it during the next full update:

```bash
java -jar diagscope.jar scan --project . --update-baseline \
  --baseline-migration sha256:<old>=sha256:<new>
```

The source must already exist in the baseline, the target must be a current finding, and the old
fingerprint must no longer be current. Migration targets suppress normally on later `--baseline`
scans. Use `--prune-removed-baseline` only after the tombstone history has been reviewed.

When `--changed-since` and `--baseline` are combined, changed-file filtering runs first, baseline
suppression runs second, and `--fail-on` evaluates the remaining findings. Baseline updates always
use the complete scan so unchanged findings are not accidentally removed.

## Output

Default files are:

```text
<analyzed-project>/target/diagscope/
├── report.md
├── result.json
└── report.html
```

Only requested formats are written. Reports are written through a temporary file and moved into place so an interrupted serialization does not leave a normal-looking partial report. The move falls back safely when the filesystem does not support atomic replacement.

The terminal summary includes tool version, source files, methods, flows, elapsed analysis time, findings, parse failures, flow boundaries, and the resolved output directory.

## Trend command

Compare two results from the same project and compatible fingerprint versions:

```bash
java -jar diagscope.jar trend \
  --base previous/result.json \
  --current current/result.json \
  --format MARKDOWN \
  --output trend.md
```

`--format` accepts `MARKDOWN` (default) or `JSON`. Findings are classified exclusively by stable
fingerprint as new, fixed, or persisting. Unsupported result schemas, mixed fingerprint versions,
duplicate fingerprints, and different project names are rejected with exit code `2`.

## Exit codes

- `0` — scan and all requested report writes completed successfully;
- `1` — scan completed, but at least one finding met the configured `--fail-on` threshold;
- `2` — invalid scan configuration or scanner/reporting failure;
- `3` — unsupported project structure or project input.

Without `--fail-on`, a successful Alpha 1 scan remains informational and exit code `0` does not mean “no findings.”

The compatibility and version-bump rules for `result.json` are documented in
[RESULT_JSON_SCHEMA.md](RESULT_JSON_SCHEMA.md).

## Supported input shape

The project directory must declare a Maven or Gradle build and expose at least one conventional Java or Kotlin/JVM production source root, either at the root or in its modules:

```text
pom.xml | build.gradle | build.gradle.kts | settings.gradle | settings.gradle.kts
src/main/java/ | src/main/kotlin/
```

Multi-module and mixed Java/Kotlin builds are supported for both tools: every nested directory (up to four levels deep) that carries its own build descriptor and a JVM source root is scanned in one run, and build output directories (`target/`, `build/`, `out/`, `bin/`) are skipped. Conventional roots and safe literal Gradle/Maven declarations are automatic; use repeatable `--source-root <path>` for dynamic production roots. Explicit roots must exist inside the project. The scanner never evaluates the build script. The detected build system and module list are reported in `result.json` (`project.buildSystem`, `project.modules`), in the Markdown summary table, and in the HTML report header.

Java analysis is syntax-first by default. Repeatable `--classpath <jar-or-classes-directory>` opts into JavaParser source/JDK/dependency symbol solving using exactly the declared entries; DiagScope does not run Maven or Gradle to construct that classpath. Kotlin analysis remains source-first but covers transitive hierarchy, defaults/varargs, generic identities, composed annotations, resource `use`, Micrometer evidence, and syntax-decidable Spring AOP. Kotlin compiler-grade dependency resolution and pointcuts requiring runtime state remain outside the boundary.

The effective explicit classpath and additional source roots are recorded in JSON and Markdown scan configuration so a resolution change is auditable.
