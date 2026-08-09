package dev.diagscope.kotlinparser;

import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.DiagnosticCoverageService;
import dev.diagscope.core.application.LocalFlowBuilder;
import dev.diagscope.core.application.rule.DynamicMetricNameRule;
import dev.diagscope.core.application.rule.HighCardinalityMetricTagRule;
import dev.diagscope.core.application.rule.MetricCreatedInLoopRule;
import dev.diagscope.core.application.rule.RuleEngine;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.MetricTagEvidence;
import dev.diagscope.core.domain.MetricValueProvenance;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class KotlinMetricPatternTest {
    @TempDir
    Path temp;

    @Test
    void recognizes_micrometer_receivers_without_matching_unrelated_kotlin_apis() {
        Map<String, List<MetricTagEvidence>> tagsByMethod = tagsByMethod();

        assertThat(tagsByMethod).containsKeys(
                "boundedLiteralTag", "boundedEnumTag", "boundedConstantTag",
                "unboundedParameterTag", "unboundedUuidTag", "registryVarargsTags",
                "staticFacadeTag", "tagsFactory", "metricInsideLoop");
        assertThat(tagsByMethod).doesNotContainKey("unrelatedFluentApi");
    }

    @Test
    void classifies_kotlin_tag_and_meter_name_provenance() {
        var analyzed = analyze();

        assertThat(method(analyzed, "boundedLiteralTag").metricTags()).singleElement().satisfies(tag -> {
            assertThat(tag.tagName()).isEqualTo("result");
            assertThat(tag.valueProvenance()).isEqualTo(MetricValueProvenance.LITERAL);
            assertThat(tag.valueBounded()).isTrue();
        });
        assertThat(method(analyzed, "boundedEnumTag").metricTags()).singleElement().satisfies(tag ->
                assertThat(tag.valueProvenance()).isEqualTo(MetricValueProvenance.ENUM_CONSTANT));
        assertThat(method(analyzed, "boundedConstantTag").metricTags()).singleElement().satisfies(tag ->
                assertThat(tag.valueProvenance()).isEqualTo(MetricValueProvenance.CONSTANT_FIELD));
        assertThat(method(analyzed, "unboundedParameterTag").metricTags()).singleElement().satisfies(tag -> {
            assertThat(tag.valueProvenance()).isEqualTo(MetricValueProvenance.PARAMETER);
            assertThat(tag.valueTypeName()).isEqualTo("String");
        });
        assertThat(method(analyzed, "unboundedUuidTag").metricTags()).singleElement().satisfies(tag -> {
            assertThat(tag.valueTypeName()).isEqualTo("UUID");
            assertThat(tag.valueIsUuid()).isTrue();
        });
        assertThat(method(analyzed, "dynamicMeterName").metricNames()).singleElement().satisfies(meter ->
                assertThat(meter.nameProvenance()).isEqualTo(MetricValueProvenance.CONCATENATION));
        assertThat(method(analyzed, "dynamicMeterNameFromParameter").metricNames()).singleElement().satisfies(meter ->
                assertThat(meter.nameProvenance()).isEqualTo(MetricValueProvenance.PARAMETER));
        assertThat(method(analyzed, "staticMeterName").metricNames()).singleElement().satisfies(meter ->
                assertThat(meter.nameProvenance()).isEqualTo(MetricValueProvenance.LITERAL));
    }

    @Test
    void runs_metric_rules_on_kotlin_flows() {
        List<Finding> findings = scan();

        assertThat(findings).extracting(Finding::ruleId)
                .contains(HighCardinalityMetricTagRule.ID, DynamicMetricNameRule.ID,
                        MetricCreatedInLoopRule.ID);
        assertThat(findings)
                .filteredOn(finding -> HighCardinalityMetricTagRule.ID.equals(finding.ruleId()))
                .anySatisfy(finding -> assertThat(finding.evidence()).containsEntry("tag", "traceId"));
        assertThat(findings)
                .filteredOn(finding -> DynamicMetricNameRule.ID.equals(finding.ruleId()))
                .extracting(finding -> finding.evidence().get("nameProvenance"))
                .contains("CONCATENATION", "PARAMETER");
    }

    @Test
    void matches_kotlin_aspect_execution_pointcuts() {
        var analyzed = analyze();

        assertThat(analyzed.aspects()).singleElement().satisfies(aspect ->
                assertThat(aspect.pointcut()).contains("KotlinMetricPatterns.dynamic*"));
        assertThat(method(analyzed, "dynamicMeterName").proxy().matchingAdvice())
                .containsExactly("example.kotlin.metrics.KotlinMetricsAspect.observe @Around");
        assertThat(method(analyzed, "boundedLiteralTag").proxy().matchingAdvice()).isEmpty();
    }

    private dev.diagscope.core.domain.AnalyzedProject analyze() {
        Path project = FixtureCatalog.copyTo(temp, "kotlin-metric-patterns");
        return new KotlinParserProjectAnalyzer().analyze(project, AnalysisOptions.defaults());
    }

    private List<Finding> scan() {
        Path project = FixtureCatalog.copyTo(temp, "kotlin-metric-patterns");
        var service = new DiagnosticCoverageService(
                new KotlinParserProjectAnalyzer(),
                new LocalFlowBuilder(),
                new RuleEngine(List.of(new HighCardinalityMetricTagRule(), new DynamicMetricNameRule(),
                        new MetricCreatedInLoopRule())));
        return service.scan(new AnalysisRequest(project, AnalysisOptions.defaults())).findings();
    }

    private Map<String, List<MetricTagEvidence>> tagsByMethod() {
        return analyze().methods().values().stream()
                .filter(method -> !method.metricTags().isEmpty())
                .collect(Collectors.toMap(method -> method.id().name(), MethodModel::metricTags,
                        (first, second) -> first));
    }

    private static MethodModel method(dev.diagscope.core.domain.AnalyzedProject project, String name) {
        return project.methods().values().stream()
                .filter(method -> method.id().declaringType().endsWith("KotlinMetricPatterns"))
                .filter(method -> method.id().name().equals(name))
                .findFirst().orElseThrow();
    }
}
