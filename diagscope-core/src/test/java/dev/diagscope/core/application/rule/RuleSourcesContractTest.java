package dev.diagscope.core.application.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.diagscope.core.application.rule.RuleSources.Basis;
import dev.diagscope.core.domain.Severity;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleSourcesContractTest {

    @Test
    void everyListedIdIsInTheCatalogAndInOneBucketOnly() {
        Set<String> catalog = RuleCatalog.all().keySet();
        Set<String> listed = new HashSet<>(RuleSources.sourcedIds());
        listed.addAll(RuleSources.patternIds());
        for (String id : listed) {
            assertTrue(catalog.contains(id), "RuleSources names a rule that is not in the catalog: " + id);
        }
        assertEquals(RuleSources.sourcedIds().size() + RuleSources.patternIds().size(), listed.size(),
                "a rule is both SOURCED and PATTERN");
    }

    @Test
    void sourcedRulesCarryWellFormedReferencesAndOthersCarryNone() {
        for (String id : RuleCatalog.all().keySet()) {
            RuleSources.Source source = RuleSources.of(id);
            if (source.basis() == Basis.SOURCED) {
                assertFalse(source.references().isEmpty(), id + " is SOURCED without a reference");
                for (String reference : source.references()) {
                    assertTrue(reference.startsWith("https://") && !reference.contains(" "),
                            id + " has a malformed reference: " + reference);
                }
            } else {
                assertTrue(source.references().isEmpty(), id + " has references but is not SOURCED");
            }
        }
    }

    @Test
    void errorRulesAreNeverUnverified() {
        for (RuleCatalog.RuleExplanation rule : RuleCatalog.all().values()) {
            if (rule.defaultSeverity() == Severity.ERROR) {
                assertTrue(RuleSources.of(rule.ruleId()).basis() != Basis.UNVERIFIED,
                        rule.ruleId() + " is ERROR but has no source: add it to RuleSources after checking the claim "
                                + "(see docs/RULE_AUDIT.md) or lower its severity");
            }
        }
    }
}
