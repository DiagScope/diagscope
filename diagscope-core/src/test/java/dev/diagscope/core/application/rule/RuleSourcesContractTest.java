package dev.diagscope.core.application.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.diagscope.core.application.rule.RuleSources.Basis;
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
    void everyRuleDeclaresItsBasis() {
        for (RuleCatalog.RuleExplanation rule : RuleCatalog.all().values()) {
            assertTrue(RuleSources.of(rule.ruleId()).basis() != Basis.UNVERIFIED,
                    rule.ruleId() + " has no basis. Add it to RuleSources.SOURCED with the references you read "
                            + "(framework or library documentation or source), or to RuleSources.PATTERN when the "
                            + "harm follows from the language, the JDK or a style heuristic and not from a "
                            + "framework. See docs/RULE_AUDIT.md.");
        }
    }
}
