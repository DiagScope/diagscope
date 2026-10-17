# diagscope-cli

CLI input adapter and Java/Kotlin composition root.

Responsibilities:

- accept arguments through Picocli;
- compose the core and adapters explicitly;
- compose the JavaParser and Kotlin PSI analyzers through the shared JVM adapter;
- generate Markdown, JSON, HTML, SARIF, and compact `AGENT` text reports;
- apply Git changed-file scope, explicit `--files` scope, stable-fingerprint baselines and waivers, then evaluate the
  project gate (`GatePolicy`: severity and confidence conditions, per-severity limits, named profiles);
- set up coding assistants (`init-agent`: instruction blocks and a Claude Code Stop hook);
- load strict project policy and expose its effective values in report metadata;
- map failures to stable exit codes;
- package an executable fat JAR.

The CLI contains no diagnostic business rules.
