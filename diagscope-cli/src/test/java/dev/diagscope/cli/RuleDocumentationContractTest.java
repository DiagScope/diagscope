package dev.diagscope.cli;

import dev.diagscope.core.application.rule.RuleCatalog;
import dev.diagscope.core.application.rule.RuleCatalog.RuleExplanation;
import dev.diagscope.core.application.rule.RuleVersions;
import dev.diagscope.core.domain.Severity;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link RuleCatalog} is the single source of truth for all registered rules.
 *
 * <p>This test enforces four invariants that must hold for every release:</p>
 * <ol>
 *   <li>Every rule registered with the engine has a catalog entry (no undocumented rules).</li>
 *   <li>The catalog contains no identifiers that are not registered with the engine
 *       (no orphan documentation).</li>
 *   <li>Every catalog entry has all required metadata fields populated and valid.</li>
 *   <li>Every registered rule has a version declared in {@link RuleVersions}.</li>
 * </ol>
 *
 * <p>Failures here indicate that a rule was added to the engine without documentation, that
 * a documentation entry was left behind after a rule was removed, or that a metadata field
 * was not filled in when a rule was introduced.</p>
 */
class RuleDocumentationContractTest {

    private static final Set<String> VALID_LANGUAGES = Set.of("java", "kotlin");
    private static final Set<String> VALID_SEVERITIES = Set.of(
            Severity.INFO.name(), Severity.WARNING.name(), Severity.ERROR.name());

    // ── Registration coverage ─────────────────────────────────────────────────

    @Test
    void every_registered_rule_has_a_catalog_entry() {
        Set<String> documented = RuleCatalog.all().keySet();
        assertThat(DiagScopeMain.registeredRuleIds())
                .as("every registered rule must have a RuleCatalog entry")
                .allSatisfy(ruleId ->
                        assertThat(documented)
                                .as("catalog entry for registered rule '%s'", ruleId)
                                .contains(ruleId));
    }

    @Test
    void catalog_contains_no_undeclared_rule_ids() {
        Set<String> registered = new HashSet<>(DiagScopeMain.registeredRuleIds());
        assertThat(RuleCatalog.all().keySet())
                .as("every catalog entry must correspond to a registered rule")
                .allSatisfy(ruleId ->
                        assertThat(registered)
                                .as("registered rule for catalog entry '%s'", ruleId)
                                .contains(ruleId));
    }

    @Test
    void registered_rule_ids_are_unique() {
        var ids = DiagScopeMain.registeredRuleIds();
        assertThat(ids).as("registered rule IDs must be unique").doesNotHaveDuplicates();
    }

    // ── Metadata completeness ─────────────────────────────────────────────────

    @Test
    void every_catalog_entry_has_non_blank_required_fields() {
        RuleCatalog.all().forEach((ruleId, doc) -> {
            assertThat(doc.ruleId()).as("%s: ruleId must not be blank", ruleId).isNotBlank();
            assertThat(doc.title()).as("%s: title must not be blank", ruleId).isNotBlank();
            assertThat(doc.category()).as("%s: category must not be blank", ruleId).isNotBlank();
            assertThat(doc.applicability()).as("%s: applicability must not be blank", ruleId).isNotBlank();
            assertThat(doc.whatItMeans()).as("%s: whatItMeans must not be blank", ruleId).isNotBlank();
            assertThat(doc.whyItMatters()).as("%s: whyItMatters must not be blank", ruleId).isNotBlank();
            assertThat(doc.howDetected()).as("%s: howDetected must not be blank", ruleId).isNotBlank();
        });
    }

    @Test
    void every_catalog_entry_has_a_non_null_default_severity() {
        RuleCatalog.all().forEach((ruleId, doc) ->
                assertThat(doc.defaultSeverity())
                        .as("%s: defaultSeverity must not be null", ruleId)
                        .isNotNull()
                        .satisfies(sev -> assertThat(VALID_SEVERITIES).contains(sev.name())));
    }

    @Test
    void every_catalog_entry_declares_at_least_one_supported_language() {
        RuleCatalog.all().forEach((ruleId, doc) -> {
            assertThat(doc.supportedLanguages())
                    .as("%s: supportedLanguages must not be empty", ruleId)
                    .isNotEmpty();
            assertThat(doc.supportedLanguages())
                    .as("%s: supportedLanguages must only contain 'java' or 'kotlin'", ruleId)
                    .isSubsetOf(VALID_LANGUAGES);
        });
    }

    @Test
    void catalog_ruleId_field_matches_the_map_key() {
        RuleCatalog.all().forEach((key, doc) ->
                assertThat(doc.ruleId())
                        .as("RuleExplanation.ruleId must match the catalog key for entry '%s'", key)
                        .isEqualTo(key));
    }

    // ── Version coverage ──────────────────────────────────────────────────────

    @Test
    void every_registered_rule_has_a_version_declared() {
        DiagScopeMain.registeredRuleIds().forEach(ruleId ->
                assertThat(RuleVersions.versionOf(ruleId))
                        .as("evidence contract version for rule '%s'", ruleId)
                        .isNotBlank()
                        .matches(Pattern.compile("\\d+\\.\\d+\\.\\d+")));
    }

    @Test
    void catalog_count_equals_registered_count() {
        int registered = DiagScopeMain.registeredRuleIds().size();
        int documented = RuleCatalog.all().size();
        assertThat(documented)
                .as("catalog must document exactly %d rules (the registered count)", registered)
                .isEqualTo(registered);
    }

    // ── Structural helpers ────────────────────────────────────────────────────

    @Test
    void known_limitations_is_null_or_non_blank() {
        RuleCatalog.all().forEach((ruleId, doc) -> {
            String limitations = doc.knownLimitations();
            if (limitations != null) {
                assertThat(limitations)
                        .as("%s: knownLimitations must be non-blank when present", ruleId)
                        .isNotBlank();
            }
        });
    }

    @Test
    void explain_returns_the_same_object_as_all() {
        RuleCatalog.all().forEach((ruleId, expected) -> {
            RuleExplanation got = RuleCatalog.explain(ruleId);
            assertThat(got)
                    .as("explain('%s') must return the catalog entry, not a fallback", ruleId)
                    .isEqualTo(expected);
        });
    }
}
