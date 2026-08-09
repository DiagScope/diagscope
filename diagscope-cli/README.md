# diagscope-cli

CLI input adapter and Java/Kotlin composition root for Alpha 1.

Responsibilities:

- accept arguments through Picocli;
- compose the core and adapters explicitly;
- compose the JavaParser and Kotlin PSI analyzers through the shared JVM adapter;
- generate Markdown, JSON, HTML, and SARIF reports;
- apply Git changed-file scope and stable-fingerprint baselines before CI severity gating;
- load strict project policy and expose its effective values in report metadata;
- map failures to stable exit codes;
- package an executable fat JAR.

The CLI contains no diagnostic business rules.
