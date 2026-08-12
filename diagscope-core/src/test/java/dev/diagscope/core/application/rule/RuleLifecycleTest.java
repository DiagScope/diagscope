package dev.diagscope.core.application.rule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleLifecycleTest {

    @Test
    void catalog_rules_are_active_and_evaluated_by_default() {
        RuleCatalog.all().keySet().forEach(ruleId -> {
            assertThat(RuleLifecycle.of(ruleId).status()).isEqualTo(RuleLifecycle.Status.ACTIVE);
            assertThat(RuleLifecycle.isEvaluated(ruleId)).isTrue();
        });
    }

    @Test
    void lifecycle_covers_every_catalog_rule_plus_reserved_retired_identifiers() {
        assertThat(RuleLifecycle.all().keySet()).containsAll(RuleCatalog.all().keySet());
        assertThat(RuleLifecycle.all().keySet()).containsAll(RuleLifecycle.retirements().keySet());
    }

    @Test
    void retired_identifiers_are_never_reused_by_the_active_catalog() {
        RuleLifecycle.retirements().forEach((ruleId, entry) -> {
            assertThat(entry.status()).isNotEqualTo(RuleLifecycle.Status.ACTIVE);
            assertThat(entry.since()).isNotBlank();
            entry.replacement().ifPresent(replacement ->
                    assertThat(RuleCatalog.all()).containsKey(replacement));
            if (entry.status() == RuleLifecycle.Status.REMOVED) {
                assertThat(RuleCatalog.all()).doesNotContainKey(ruleId);
                assertThat(RuleLifecycle.isEvaluated(ruleId)).isFalse();
            }
        });
    }
}
