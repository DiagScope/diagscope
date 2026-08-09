package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleCatalogTest {

    private static final List<String> SHIPPED_RULES = List.of(
            SilentCatchRule.ID,
            SilentFailureConversionRule.ID,
            IgnoredKafkaSendResultRule.ID,
            KafkaManualAckMissingRule.ID,
            KafkaListenerFailureNotPropagatedRule.ID,
            TransactionalRollbackSuppressedRule.ID,
            JdbcResourceLeakRule.ID,
            DatabaseResourceCloseNotGuardedRule.ID,
            EntityManagerLeakRule.ID,
            JdbcTemplateConnectionEscapeRule.ID,
            HighCardinalityMetricTagRule.ID,
            DynamicMetricNameRule.ID,
            PrintStackTraceRule.ID,
            SystemOutputRule.ID,
            SelfInvocationProxyBypassRule.ID,
            NonProxyableAdviceTargetRule.ID,
            UnmanagedAdviceTargetRule.ID,
            LogWithoutThrowableRule.ID,
            GenericExceptionMessageRule.ID,
            AsyncResultUnobservedRule.ID,
            HttpClientErrorDiscardedRule.ID,
            ScheduledTaskSwallowsFailureRule.ID,
            RetryWithoutDiagnosticsRule.ID,
            FallbackHidesFailureRule.ID,
            MetricCreatedInLoopRule.ID,
            SensitivePayloadLoggedRule.ID,
            MdcContextLostRule.ID,
            DuplicateDiagnosticSignalRule.ID,
            TransactionalPropagationMismatchRule.ID);

    @Test
    void every_shipped_rule_has_a_detailed_explanation() {
        for (String ruleId : SHIPPED_RULES) {
            var explanation = RuleCatalog.explain(ruleId);
            assertThat(explanation.ruleId()).isEqualTo(ruleId);
            assertThat(explanation.title()).isNotBlank();
            assertThat(explanation.whatItMeans()).isNotBlank();
            assertThat(explanation.whyItMatters()).isNotBlank();
            assertThat(explanation.howDetected()).isNotBlank();
        }
        assertThat(RuleCatalog.all().keySet()).containsExactlyInAnyOrderElementsOf(SHIPPED_RULES);
    }

    @Test
    void unknown_rules_fall_back_to_a_neutral_explanation() {
        var explanation = RuleCatalog.explain("CUSTOM_RULE");
        assertThat(explanation.ruleId()).isEqualTo("CUSTOM_RULE");
        assertThat(explanation.whyItMatters()).isNotBlank();
    }

    @Test
    void every_confidence_level_states_what_it_means_for_triage() {
        for (Confidence confidence : Confidence.values()) {
            assertThat(RuleCatalog.confidenceRationale(confidence))
                    .startsWith(confidence.name() + " —")
                    .hasSizeGreaterThan(40);
        }
    }
}
