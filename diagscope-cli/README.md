# diagscope-cli

CLI input adapter and composition root for Alpha 1.

Responsibilities:

- accept arguments through Picocli;
- compose the core and adapters explicitly;
- generate Markdown and JSON reports;
- map failures to stable exit codes;
- package an executable fat JAR.

The CLI contains no diagnostic business rules.
