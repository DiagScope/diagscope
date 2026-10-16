package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AutowiredRequiredFalseUnsafeRuleTest {

    @Test
    void reportsACallOnAnOptionalFieldWithoutANullCheck() {
        var findings = new AutowiredRequiredFalseUnsafeRule().evaluate(flow(method(
                List.of(call(10, "audit", "record")), Map.of())));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.ruleId()).isEqualTo(AutowiredRequiredFalseUnsafeRule.ID);
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.confidence()).isEqualTo(Confidence.MEDIUM);
            assertThat(finding.evidence()).containsEntry("field", "audit")
                    .containsEntry("method", "example.Service.run()");
        });
    }

    @Test
    void reportsEachFieldOncePerMethod() {
        var findings = new AutowiredRequiredFalseUnsafeRule().evaluate(flow(method(
                List.of(call(10, "audit", "record"), call(11, "audit", "flush"), call(12, "this.audit", "close")),
                Map.of())));

        assertThat(findings).hasSize(1);
    }

    @Test
    void understandsThisQualifiedAccess() {
        var findings = new AutowiredRequiredFalseUnsafeRule().evaluate(flow(method(
                List.of(call(10, "this.audit", "record")), Map.of())));

        assertThat(findings).singleElement().satisfies(finding ->
                assertThat(finding.evidence()).containsEntry("field", "audit"));
    }

    @Test
    void ignoresAFieldTheMethodComparesWithNull() {
        var checked = Map.of(MethodModel.NULL_CHECKED_ANNOTATION, Map.of("names", "audit"));
        assertThat(new AutowiredRequiredFalseUnsafeRule().evaluate(flow(method(
                List.of(call(10, "audit", "record")), checked)))).isEmpty();
    }

    @Test
    void ignoresCallsOnOtherReceivers() {
        assertThat(new AutowiredRequiredFalseUnsafeRule().evaluate(flow(method(
                List.of(call(10, "repository", "save")), Map.of())))).isEmpty();
    }

    @Test
    void ignoresClassesWithoutOptionalFields() {
        var plain = new MethodModel(new MethodId("example.Service", "run", List.of()), location(1), Set.of(),
                List.of(), List.of(call(10, "audit", "record")), List.of(), List.of(), List.of());
        assertThat(new AutowiredRequiredFalseUnsafeRule().evaluate(flow(plain))).isEmpty();
    }

    private static MethodModel method(List<InvocationEvidence> invocations,
                                      Map<String, Map<String, String>> extraAttributes) {
        var attributes = new java.util.LinkedHashMap<>(extraAttributes);
        attributes.put(MethodModel.OPTIONAL_AUTOWIRED_ANNOTATION, Map.of("fields", "audit"));
        return new MethodModel(new MethodId("example.Service", "run", List.of()), location(1), Set.of(),
                List.of(), invocations, List.of(), List.of(), List.of(), ProxyProfile.unknown(), attributes,
                CallableShape.fixed(0), "void", false);
    }

    private static InvocationEvidence call(int line, String scope, String method) {
        return new InvocationEvidence(location(line), scope, "AuditClient", method, List.of(),
                InvocationResultUsage.IGNORED);
    }

    private static Flow flow(MethodModel method) {
        var entrypoint = new Entrypoint(EntrypointType.REST, method.id(), "POST /run", method.location());
        return new Flow(entrypoint, List.of(new FlowMethod(method, 0, Confidence.HIGH, List.of(method.id()))),
                List.of());
    }

    private static SourceLocation location(int line) {
        return new SourceLocation(Path.of("src/main/java/example/Service.java"), line, line);
    }
}
