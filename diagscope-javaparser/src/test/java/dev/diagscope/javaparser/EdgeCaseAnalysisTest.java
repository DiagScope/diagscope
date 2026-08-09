package dev.diagscope.javaparser;

import dev.diagscope.core.application.LocalFlowBuilder;

import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisRequest;
import dev.diagscope.core.application.DiagnosticCoverageService;
import dev.diagscope.core.application.rule.PrintStackTraceRule;
import dev.diagscope.core.application.rule.RuleEngine;
import dev.diagscope.core.application.rule.SystemOutputRule;
import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Entrypoint;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.testfixtures.FixtureCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boundary coverage for source shapes that break naive identity or discovery assumptions:
 * default-package types, nested classes, records, enums, and overloaded methods.
 */
class EdgeCaseAnalysisTest {
    @TempDir
    Path temp;

    @Test
    void keeps_default_package_nested_and_overloaded_methods_distinguishable() {
        AnalyzedProject analyzed = analyze();

        assertThat(analyzed.parseFailures()).isEmpty();
        assertThat(analyzed.methods().keySet().stream().map(MethodId::displayName))
                .contains(
                        "DefaultPackageController.read(String)",
                        "DefaultPackageService.read(String)",
                        "example.edge.NestedJob.Inner.reconcile()",
                        "example.edge.NestedJob.Inner.perform()",
                        "example.edge.NestedJob.Reference.describe()",
                        "example.edge.NestedJob.Mode.fast()",
                        "example.edge.OverloadService.process(String)",
                        "example.edge.OverloadService.process(String,int)",
                        "example.edge.OverloadService.process(String,String)")
                .doesNotHaveDuplicates();
    }

    @Test
    void detects_entrypoints_in_default_package_and_nested_class_hosts() {
        AnalyzedProject analyzed = analyze();

        assertThat(analyzed.entrypoints())
                .extracting(Entrypoint::type, Entrypoint::displayName)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(EntrypointType.REST, "GET /default-package"),
                        org.assertj.core.groups.Tuple.tuple(EntrypointType.REST, "POST /overloads"),
                        org.assertj.core.groups.Tuple.tuple(EntrypointType.SCHEDULED, "Scheduled cron=0 0 * * * *"));
    }

    @Test
    void reaches_default_package_and_nested_findings_through_real_flows() {
        Path project = FixtureCatalog.copyTo(temp, "edge-cases");
        var service = new DiagnosticCoverageService(
                new JavaParserProjectAnalyzer(),
                new LocalFlowBuilder(),
                new RuleEngine(List.of(new PrintStackTraceRule(), new SystemOutputRule())));

        var result = service.scan(new AnalysisRequest(project, AnalysisOptions.defaults()));

        assertThat(result.flows()).extracting(flow -> flow.entrypoint().displayName())
                .contains("GET /default-package", "POST /overloads", "Scheduled cron=0 0 * * * *");

        Flow defaultPackageFlow = flow(result.flows(), "GET /default-package");
        assertThat(defaultPackageFlow.methods()).extracting(method -> method.method().id().displayName())
                .contains("DefaultPackageService.read(String)");

        // Literal argument types make the same-arity overloads source-decidable.
        Flow overloadFlow = flow(result.flows(), "POST /overloads");
        assertThat(overloadFlow.methods()).extracting(method -> method.method().id().displayName())
                .contains("example.edge.OverloadService.process(String)")
                .contains("example.edge.OverloadService.process(String,int)")
                .contains("example.edge.OverloadService.process(String,String)");
        assertThat(overloadFlow.boundaries())
                .noneSatisfy(boundary -> assertThat(boundary.resolutionReason())
                        .isEqualTo(dev.diagscope.core.domain.ResolutionReason.AMBIGUOUS));

        assertThat(result.findings()).extracting(Finding::ruleId).contains("PRINT_STACK_TRACE", "SYSTEM_OUTPUT");
        assertThat(result.findings()).extracting(Finding::fingerprint).doesNotHaveDuplicates();
        assertThat(result.findings())
                .anySatisfy(finding -> assertThat(finding.location().file().toString())
                        .endsWith("src/main/java/DefaultPackageService.java"))
                .anySatisfy(finding -> assertThat(finding.location().file().toString())
                        .endsWith("src/main/java/example/edge/NestedJob.java"));
    }

    private static Flow flow(List<Flow> flows, String displayName) {
        return flows.stream()
                .filter(flow -> flow.entrypoint().displayName().equals(displayName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing flow: " + displayName));
    }

    private AnalyzedProject analyze() {
        Path project = FixtureCatalog.copyTo(temp, "edge-cases");
        return new JavaParserProjectAnalyzer().analyze(project, AnalysisOptions.defaults());
    }
}
