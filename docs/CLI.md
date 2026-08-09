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
- `--changed-since <ref>` — retain findings only when their source file appears in `git diff <ref>`.
- `--config <path>` — load a strict project policy; without this option, `diagscope.yml` is loaded
  automatically from the project root when present.

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

Multi-module and mixed Java/Kotlin builds are supported for both tools: every nested directory (up to four levels deep) that carries its own build descriptor and a conventional JVM source root is scanned in one run, and build output directories (`target/`, `build/`, `out/`, `bin/`) are skipped. The detected build system and module list are reported in `result.json` (`project.buildSystem`, `project.modules`), in the Markdown summary table, and in the HTML report header.

Kotlin analysis is syntax-first. It currently covers entrypoints, local and single-implementation interface calls, catch and invocation evidence, resource `use`, Micrometer tags and names, metric creation inside loops, and syntax-decidable Spring AOP pointcuts. Complete type/classpath resolution and pointcuts that require runtime state remain outside the current boundary.

Source directories configured explicitly inside a build script (custom `sourceSets` or `build-helper` roots) and generated sources are still out of scope: DiagScope never executes Maven or Gradle, it only reads the conventional layout.
