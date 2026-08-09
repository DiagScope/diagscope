package dev.diagscope.javaparser;

import dev.diagscope.core.application.LocalFlowBuilder;

import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.DiagnosticCoverageService;
import dev.diagscope.core.application.rule.DynamicMetricNameRule;
import dev.diagscope.core.application.rule.HighCardinalityMetricTagRule;
import dev.diagscope.core.application.rule.RuleEngine;
import dev.diagscope.core.domain.Confidence;
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

/**
 * Adapter-level contract for Micrometer receiver recognition, tag key/value/type/provenance
 * analysis, and dynamic meter names.
 */
class MetricPatternTest {
    @TempDir
    Path temp;

    @Test
    void recognizes_builder_registry_static_facade_and_tag_factory_receivers() {
        Map<String, List<MetricTagEvidence>> tagsByMethod = tagsByMethod();

        assertThat(tagsByMethod).containsKeys(
                "boundedLiteralTag", "boundedEnumTag", "boundedConstantTag",
                "unboundedParameterTag", "unboundedUuidTag",
                "registryVarargsTags", "staticFacadeTag", "tagsFactory");
        assertThat(tagsByMethod).doesNotContainKey("unrelatedFluentApi");
    }

    @Test
    void classifies_tag_value_provenance_and_type() {
        Map<String, List<MetricTagEvidence>> tagsByMethod = tagsByMethod();

        assertThat(tagsByMethod.get("boundedLiteralTag")).singleElement().satisfies(tag -> {
            assertThat(tag.tagName()).isEqualTo("result");
            assertThat(tag.valueProvenance()).isEqualTo(MetricValueProvenance.LITERAL);
            assertThat(tag.valueBounded()).isTrue();
        });
        assertThat(tagsByMethod.get("boundedEnumTag")).singleElement().satisfies(tag ->
                assertThat(tag.valueProvenance()).isEqualTo(MetricValueProvenance.ENUM_CONSTANT));
        assertThat(tagsByMethod.get("boundedConstantTag")).singleElement().satisfies(tag ->
                assertThat(tag.valueProvenance()).isEqualTo(MetricValueProvenance.CONSTANT_FIELD));
        assertThat(tagsByMethod.get("unboundedParameterTag")).singleElement().satisfies(tag -> {
            assertThat(tag.valueProvenance()).isEqualTo(MetricValueProvenance.PARAMETER);
            assertThat(tag.valueTypeName()).isEqualTo("String");
            assertThat(tag.valueBounded()).isFalse();
        });
        assertThat(tagsByMethod.get("unboundedUuidTag")).singleElement().satisfies(tag -> {
            assertThat(tag.valueTypeName()).isEqualTo("UUID");
            assertThat(tag.valueIsUuid()).isTrue();
        });
        assertThat(tagsByMethod.get("registryVarargsTags"))
                .extracting(MetricTagEvidence::tagName)
                .containsExactly("result", "orderId");
    }

    @Test
    void reports_unbounded_tags_and_skips_bounded_values() {
        var findings = scan();

        assertThat(findings.stream()
                .filter(finding -> HighCardinalityMetricTagRule.ID.equals(finding.ruleId()))
                .map(finding -> finding.evidence().get("method"))
                .collect(Collectors.toSet()))
                .allSatisfy(method -> assertThat(method)
                        .doesNotContain("boundedLiteralTag")
                        .doesNotContain("boundedEnumTag")
                        .doesNotContain("boundedConstantTag"));
        assertThat(findings)
                .filteredOn(finding -> HighCardinalityMetricTagRule.ID.equals(finding.ruleId()))
                .isNotEmpty()
                .anySatisfy(finding -> {
                    assertThat(finding.evidence()).containsEntry("tag", "traceId");
                    assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);
                });
    }

    @Test
    void reports_dynamic_meter_names_and_not_constant_names() {
        var dynamicFindings = scan().stream()
                .filter(finding -> DynamicMetricNameRule.ID.equals(finding.ruleId()))
                .toList();

        assertThat(dynamicFindings)
                .extracting(finding -> finding.evidence().get("nameProvenance"))
                .contains("CONCATENATION", "PARAMETER");
        assertThat(dynamicFindings)
                .extracting(finding -> finding.evidence().get("method"))
                .noneMatch(method -> method.contains("staticMeterName"));
    }

    private List<Finding> scan() {
        Path project = FixtureCatalog.copyTo(temp, "metric-patterns");
        var service = new DiagnosticCoverageService(
                new JavaParserProjectAnalyzer(),
                new LocalFlowBuilder(),
                new RuleEngine(List.of(new HighCardinalityMetricTagRule(), new DynamicMetricNameRule())));
        return service.scan(new AnalysisRequest(project, AnalysisOptions.defaults())).findings();
    }

    private Map<String, List<MetricTagEvidence>> tagsByMethod() {
        Path project = FixtureCatalog.copyTo(temp, "metric-patterns");
        var analyzed = new JavaParserProjectAnalyzer().analyze(project, AnalysisOptions.defaults());
        return analyzed.methods().values().stream()
                .filter(method -> !method.metricTags().isEmpty())
                .collect(Collectors.toMap(
                        method -> method.id().name(),
                        MethodModel::metricTags,
                        (first, second) -> first));
    }
}
