---
name: project-rule-count
description: Current total rule count and wave history for DiagScope
metadata:
  type: project
---

As of 2026-09-23 (Wave 7), DiagScope has **95 registered rules**.

Wave history:
| Wave | Date | Added | Total |
|---|---|---|---|
| Original | 2026 | 62 | 62 |
| Wave 2 | 2026-08-26 | +7 | 69 |
| Wave 3 | 2026-08-26 | +3 | 72 |
| Wave 4 | 2026-08-26 | +5 | 77 |
| Wave 5 | 2026-08-27 | +5 | 82 |
| Wave 6 | 2026-09-03 | +7 | 89 |
| Wave 7 | 2026-09-23 | +6 | 95 |

Wave 6 rules: `OPTIONAL_OR_ELSE_NULL`, `MAP_GET_DEREFERENCED_WITHOUT_CHECK`, `TRANSACTION_ISOLATION_DANGEROUS`, `REQUIRES_NEW_IN_LOOP`, `JPA_BATCH_LOOP_WITHOUT_FLUSH_CLEAR`, `ENTITY_MANAGER_FIND_DEREFERENCE`, `READONLY_TRANSACTION_WRITE`.

Wave 7 rules: `EXCEPTION_CONSTRUCTOR_WITHOUT_MESSAGE`, `PROPAGATION_SUPPORTS_WRITE_RISK`, `STREAM_IO_NOT_CLOSED`, `LOG_MESSAGE_STRING_CONCAT`, `CACHE_NAME_MISMATCH`, `SCHEDULED_FIXED_RATE_TOO_AGGRESSIVE`.

**Why:** User asked to update docs and ensure all rules have motivos + ajuda de solução.
**How to apply:** When adding new rules, update: (1) `docs/RULE_CANDIDATES.md` Wave section + count table, (2) `docs/RULES.md` with the new rule entry, (3) `RuleRemediationCatalog.java` with code snippets.
