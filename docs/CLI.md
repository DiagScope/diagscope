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
  --format MARKDOWN,JSON
```

## Options

- `-p`, `--project` — required directory for one conventional Maven module;
- `-o`, `--output` — output directory, default `target/diagscope`; a relative path is resolved below the analyzed project and cannot escape it with `..`; an absolute path is used explicitly as supplied;
- `--max-depth` — maximum local call depth, from `0` through `32`;
- `--parallelism` — parser worker count; `0` selects the automatic bounded policy;
- `--entrypoint` — comma-separated subset of `REST`, `KAFKA_LISTENER`, and `SCHEDULED`;
- `--format` — `MARKDOWN`, `JSON`, or both.

The automatic worker policy may also be selected with the `diagscope.parallelism` system property. Explicit command options take precedence.

## Output

Default files are:

```text
<analyzed-project>/target/diagscope/
├── report.md
└── result.json
```

Only requested formats are written. Reports are written through a temporary file and moved into place so an interrupted serialization does not leave a normal-looking partial report. The move falls back safely when the filesystem does not support atomic replacement.

The terminal summary includes tool version, source files, methods, flows, elapsed analysis time, findings, parse failures, flow boundaries, and the resolved output directory.

## Exit codes

- `0` — scan and all requested report writes completed successfully;
- `2` — invalid scan configuration or scanner/reporting failure;
- `3` — unsupported project structure or project input.

An Alpha 1 success exit does not mean “no findings.” Severity thresholds and baseline-aware CI blocking are deliberately postponed until project configuration exists.

## Supported input shape

The project directory must declare a Maven or a Gradle build and expose conventional Java sources, either at the root or in its modules:

```text
pom.xml | build.gradle | build.gradle.kts | settings.gradle | settings.gradle.kts
src/main/java/
```

Multi-module builds are supported for both tools: every nested directory (up to four levels deep) that carries its own build descriptor and a `src/main/java` folder is scanned in one run, and build output directories (`target/`, `build/`, `out/`, `bin/`) are skipped. The detected build system and module list are reported in `result.json` (`project.buildSystem`, `project.modules`), in the Markdown summary table, and in the HTML report header.

Source directories configured explicitly inside a build script (custom `sourceSets` or `build-helper` roots) and generated sources are still out of scope: DiagScope never executes Maven or Gradle, it only reads the conventional layout.
