package dev.diagscope.jvmanalysis;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AspectPointcutMatcherTest {
    private static final AspectPointcutMatcher.Target KOTLIN_TARGET =
            new AspectPointcutMatcher.Target("sample.payment.KotlinService", "execute", Set.of("Observed"));

    @Test
    void matches_annotation_within_and_execution_designators() {
        assertThat(AspectPointcutMatcher.matches("@annotation(example.Observed)", KOTLIN_TARGET)).isTrue();
        assertThat(AspectPointcutMatcher.matches("within(sample.payment..*)", KOTLIN_TARGET)).isTrue();
        assertThat(AspectPointcutMatcher.matches(
                "execution(* sample.payment.KotlinService.execute(..))", KOTLIN_TARGET)).isTrue();
    }

    @Test
    void keeps_unknown_or_excluded_targets_unmatched() {
        assertThat(AspectPointcutMatcher.matches("bean(paymentService)", KOTLIN_TARGET)).isFalse();
        assertThat(AspectPointcutMatcher.matches(
                "within(sample.payment..*) && !bean(paymentService)", KOTLIN_TARGET)).isFalse();
        assertThat(AspectPointcutMatcher.matches(
                "within(sample.payment..*) && !@annotation(Observed)", KOTLIN_TARGET)).isFalse();
    }

    @Test
    void gives_and_higher_precedence_than_or() {
        assertThat(AspectPointcutMatcher.matches(
                "@annotation(Observed) || within(other..*) && execution(* missing(..))", KOTLIN_TARGET))
                .isTrue();
    }
}
