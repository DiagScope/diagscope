# ADR-0008: A Maven plugin that embeds the engine and reuses the CLI's gate

## Status

Accepted and implemented (`diagscope-maven-plugin`, unreleased).

## Context

Teams want the analysis to run inside the build and to choose how much a report may contain before the build fails.
The GitHub Action and the Gradle script shell out to the CLI. A Maven build has a plugin model, a lifecycle to bind to,
and its own logging, and a plugin is how organisations enforce a check on every module without touching CI.

## Decision

1. **In process, not a fork.** The mojo calls `DiagScopeMain.createScanWorkflow()`, the documented embedding entry point
   ([CI.md](../CI.md)), so the plugin, the CLI and the Gradle script cannot drift in what a scan or a gate means. No
   second implementation of the gate exists.
2. **Depend on the shaded CLI jar, exclude its transitive dependencies.** The jar already contains the analyzer, the
   parsers, Jackson and Picocli. Maven isolates a plugin's class path from the project's, so bundled copies cannot
   clash with the build's own dependencies. The plugin jar stays small; the cost is a 78 MB download per Maven
   repository, once.
3. **Tolerance is the CLI's.** `failOn`, `failOnConfidence` and `gateProfile` map one to one to the CLI options, and
   the `gate:` section of `diagscope.yml` is read by the same loader. The plugin adds nothing to the model.
4. **Parity over convenience.** The plugin does not resolve the project's dependencies for the symbol solver, although
   Maven could provide them. Doing so would make results differ from `diagscope scan` and from the calibration corpus,
   force dependency resolution on every build and fail offline. It can be added as an opt-in once measured.
5. **Per module.** A scan judges one module's directory. A repository-wide gate is not a plugin concern: the root
   `diagscope.yml` is found from each module, and a CLI scan at the root remains the whole-repository view.
6. **Skip, do not fail, on a module without sources.** `UnsupportedProjectException` becomes a warning, otherwise a
   multi-module build with a documentation-only module could not enable the plugin.

## Not decided here

- **Publication.** Maven Central needs a verified namespace; a repository on the site would need to host the 78 MB jar
  per release. Until one is chosen the plugin is installed from source or from an internal repository.
- **The Java baseline.** Maven must run on JDK 25. The code compiles with `--release 17` today, but supporting 17 or 21
  means a CI matrix and a change to ADR-0002, which is a separate decision.
- **Opt-in project classpath, `PUBLIC_METHOD` by default, an aggregate goal.**

## Consequences

- A build fails on `mvn verify` with no CI changes, and the same `diagscope.yml` governs the CLI, CI and the build.
- Anyone adopting it needs JDK 25 for Maven until the baseline decision is made.
- The CLI's embedding API is now load-bearing: changing `ScanWorkflow.Request` breaks the plugin, so it keeps compatible
  constructors, as the CLI already does.
