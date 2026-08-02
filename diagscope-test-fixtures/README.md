# diagscope-test-fixtures

Small source projects used by integration tests.

Each rule should include:

- a positive case;
- a negative case;
- a boundary or ambiguous case;
- the expected confidence level.

Fixtures are Java sources analyzed by DiagScope. They do not need to compile as complete Spring applications unless a specific test requires compilation.

## Fixture catalog

- `mixed-flow` exercises multiple entrypoint and diagnostic rule types in one small project.
- `silent-catch` defines the alpha contract for empty catch blocks, logging, explanatory comments, and explicit rule suppression. Its `expected.json` records the exact positive and negative locations.

An intentional suppression must use `diagscope:ignore <RULE_ID> -- <reason>`. A regular explanatory comment remains diagnostic evidence rather than silently disabling a rule.
