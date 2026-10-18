# diagscope-maven-plugin

Maven adapter: the `scan` goal runs DiagScope's `ScanWorkflow` on each module and fails the build when the gate is
breached.

Responsibilities:

- map Maven parameters (`diagscope.failOn`, `diagscope.gateProfile`, ...) to a `ScanWorkflow.Request`;
- find the project policy in the module, then in a parent module's directory;
- send the scan's output to the Maven log and turn a breached gate into a `MojoFailureException`;
- skip `pom` modules and modules without sources.

It contains no analysis and no gate logic. Usage and parameters: [docs/MAVEN_PLUGIN.md](../docs/MAVEN_PLUGIN.md); design:
[ADR-0008](../docs/decisions/ADR-0008-maven-plugin.md).
