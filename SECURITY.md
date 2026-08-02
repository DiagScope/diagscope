# Security

DiagScope is designed to run locally and does not upload source code.

Security-sensitive changes include:

- adding network access;
- executing code from the analyzed project;
- loading arbitrary application classes;
- following symbolic links outside the configured project root;
- writing outside the configured output directory;
- including source snippets or secrets in remote telemetry.

Alpha 1 must not execute application code or initialize the analyzed Spring context.

## Reporting a vulnerability

Do not publish suspected vulnerabilities in a public issue. Use the private security-reporting channel configured for the repository once it is available.
