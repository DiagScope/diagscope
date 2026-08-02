# Contributing

## Development rules

1. Preserve the dependency direction defined by the hexagonal architecture.
2. JavaParser types must never cross into `diagscope-core`.
3. Every rule or bug fix must include an appropriate fixture or focused test.
4. Every finding must include evidence, source location, severity, and confidence.
5. Performance optimizations require benchmark or JFR evidence.
6. Do not add default-level logging inside AST, method, or source-file loops.
7. The same source tree and configuration must produce deterministic output.
8. Prefer a conservative unresolved result over guessing.

## Pull request checklist

- [ ] `mvn clean verify` passes with JDK 25.
- [ ] New rules include positive, negative, and boundary cases.
- [ ] Public behavior and rule semantics are documented.
- [ ] Performance-sensitive changes include measurements from the same corpus and environment.
- [ ] `diagscope-core` does not depend on adapter libraries.
- [ ] Generated output remains deterministic.
