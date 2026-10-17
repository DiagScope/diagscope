# diagscope-core

Framework-independent analysis core.

This module contains:

- immutable domain models;
- input and output ports;
- `DiagnosticCoverageService`;
- deterministic rules, each with a declared basis (`RuleSources`) and a version (`RuleVersions`);
- finding deduplication;
- analysis statistics and phase metrics.

This module must not depend on JavaParser, Picocli, Jackson, Spring, or any other adapter technology.
