# Maven plugin

`dev.diagscope:diagscope-maven-plugin` runs DiagScope during a Maven build and fails the build according to a gate you
configure. It runs the same engine as `diagscope scan`, in the Maven process, on each module's directory, so a result
here matches the command line.

## Availability and requirements

- **Not published to Maven Central yet.** Build it from source (`mvn install`) or deploy it to your organisation's
  repository (`mvn deploy -DaltDeploymentRepository=...`). The plugin jar is small; it depends on the
  `diagscope-cli` jar (about 78 MB, the analyzer, both parsers and the Kotlin compiler), which Maven downloads once.
- **Maven must run on JDK 17 or newer** ([ADR-0009](decisions/ADR-0009-java-17-runtime-baseline.md)). CI starts Maven
  on 17 and runs the plugin against a sample project. Building the plugin itself needs JDK 21 or newer.
- Maven 3.9 or newer.

## Use

```xml
<plugin>
  <groupId>dev.diagscope</groupId>
  <artifactId>diagscope-maven-plugin</artifactId>
  <version>0.1.5</version>
  <executions>
    <execution>
      <goals><goal>scan</goal></goals>   <!-- bound to the verify phase -->
    </execution>
  </executions>
</plugin>
```

Or on demand: `mvn dev.diagscope:diagscope-maven-plugin:0.1.5:scan -Ddiagscope.failOn=ERROR`.

With no gate configured the scan only reports. Reports go to `target/diagscope` of each module.

## Tolerance

Tolerance is the same model as the CLI ([CONFIGURATION.md](CONFIGURATION.md#gate)). Put it in `diagscope.yml` so it is
reviewed with the code:

```yaml
schemaVersion: "1.0"
gate:
  failOn:
    - severity: ERROR
      confidence: HIGH
  maxFindings:
    WARNING: 20
  profiles:
    release:
      failOn:
        - severity: WARNING
```

Select a profile per build: `mvn verify -Ddiagscope.gateProfile=release`. A `failOn` parameter replaces the gate, and
combining it with `gateProfile` is an error, as on the command line.

## Parameters

| Parameter | Property | Default | Meaning |
| --- | --- | --- | --- |
| `skip` | `diagscope.skip` | `false` | Do nothing. |
| `failOn` | `diagscope.failOn` | none | `ERROR`, `WARNING`, `INFO` or `NONE`; overrides the project gate. |
| `failOnConfidence` | `diagscope.failOnConfidence` | `LOW` | With `failOn`, minimum confidence that counts. |
| `gateProfile` | `diagscope.gateProfile` | none | A profile of the `gate:` section. |
| `baseline` | `diagscope.baseline` | none | Baseline file; its findings are not judged. |
| `configuration` | `diagscope.config` | see below | Policy file. |
| `changedSince` | `diagscope.changedSince` | none | Only findings in files changed since a Git revision. |
| `outputDirectory` | `diagscope.outputDirectory` | `${project.build.directory}/diagscope` | Where reports go. |
| `formats` | `diagscope.formats` | `MARKDOWN,JSON,HTML` | Also `SARIF`, `AGENT`. |
| `entrypoints` | `diagscope.entrypoints` | `REST,KAFKA_LISTENER,REACTIVE_MESSAGE,SCHEDULED` | Add `PUBLIC_METHOD` for libraries and for code no endpoint reaches. |
| `includeTests` | `diagscope.includeTests` | `false` | Also analyze `src/test` with the test rules. |
| `maxDepth` | `diagscope.maxDepth` | `3` | Call depth, 0 to 32. |

## Behaviour to know

- **Per module.** In a reactor the plugin runs once per module, and a gate (including `maxFindings`) judges that module on
  its own. A module with `pom` packaging is skipped. A module with no recognisable sources logs a warning and is skipped,
  it does not fail the build.
- **Policy lookup.** `configuration`, then the module's own `diagscope.yml`, then the nearest `diagscope.yml` in a
  parent module's directory, so one file at the repository root governs every module.
- **No project classpath.** Like the CLI without `--classpath`, the plugin does not resolve dependencies. Calls into
  libraries are flow boundaries. This keeps results identical to the command line and the build offline.
- **Log output.** The scan summary and the breached gate conditions appear in the Maven log; a breached gate is a
  `MojoFailureException` naming the reports directory.

## CI

The plugin needs no separate CI step: `mvn verify` fails when the gate does. Keep the reports as build artifacts:

```yaml
- run: mvn -B verify
- uses: actions/upload-artifact@v4
  if: always()
  with:
    name: diagscope
    path: '**/target/diagscope/'
```

For a legacy codebase, record a baseline with the CLI (`diagscope scan --update-baseline`), commit it, and point
`baseline` at it, so the build judges only new findings.
