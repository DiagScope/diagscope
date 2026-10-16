package dev.diagscope.core.application.rule;

import dev.diagscope.core.application.AnalysisPolicy;
import dev.diagscope.core.domain.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestSourceRulesTest {
    private static final String TEST_FILE = "src/test/java/example/OrderTest.java";

    // ── scope isolation ──────────────────────────────────────────────────────

    @Test
    void productionRulesNeverSeeTestMethods_andTestRulesNeverSeeProduction() {
        var production = method("example.Service", "run", "src/main/java/example/Service.java", Set.of(),
                invocation("repo", "save"), invocation("repo", "save"));
        var test = method("example.OrderTest", "runs", TEST_FILE, Set.of("Test"), invocation("service", "run"));
        var seenByProduction = new java.util.ArrayList<String>();
        var seenByTests = new java.util.ArrayList<String>();
        ProjectRule productionRule = new ProjectRule() {
            public String id() { return "PRODUCTION_PROBE"; }
            public List<Finding> evaluate(AnalyzedProject project) {
                project.methods().values().forEach(m -> seenByProduction.add(m.id().displayName()));
                return List.of();
            }
        };
        ProjectRule testRule = new ProjectRule() {
            public String id() { return "TEST_PROBE"; }
            public boolean testSourceRule() { return true; }
            public List<Finding> evaluate(AnalyzedProject project) {
                project.methods().values().forEach(m -> seenByTests.add(m.id().displayName()));
                return List.of();
            }
        };

        new RuleEngine(List.of(), List.of(productionRule, testRule))
                .run(List.of(), project(production, test), AnalysisPolicy.defaults());

        assertThat(seenByProduction).containsExactly("example.Service.run()");
        assertThat(seenByTests).containsExactly("example.OrderTest.runs()");
    }

    @Test
    void testRulesAreSkippedWhenNoTestSourceWasAnalyzed() {
        var production = method("example.Service", "run", "src/main/java/example/Service.java", Set.of("Test"),
                invocation("repo", "save"));
        var findings = new RuleEngine(List.of(), List.of(new TestWithoutAssertionRule()))
                .run(List.of(), project(production), AnalysisPolicy.defaults());
        assertThat(findings).isEmpty();
    }

    // ── TEST_WITHOUT_ASSERTION ───────────────────────────────────────────────

    @Test
    void testWithoutAssertion_reportsRunOnlyTests() {
        var bare = test("bare", invocation("service", "run"));
        var asserting = test("asserting", invocation("service", "run"), invocation("assertEquals"));
        var empty = test("contextLoads");
        var vertx = test("vertx", invocation("testContext", "completeNow"));
        var awaitility = test("awaitility", invocation("", "await"));
        assertThat(new TestWithoutAssertionRule().evaluate(project(bare, asserting, empty, vertx, awaitility)))
                .singleElement().satisfies(f -> assertThat(f.evidence())
                        .containsEntry("method", "example.OrderTest.bare()"));
    }

    @Test
    void testWithoutAssertion_followsAHelperTheTestCalls() {
        var helper = test("expectCreated", invocation("assertThat"));
        helper = withoutTestAnnotation(helper);
        var viaHelper = new MethodModel(new MethodId("example.OrderTest", "viaHelper", List.of()),
                location(TEST_FILE, 5), Set.of("Test"), List.of(), List.of(invocation("service", "run")), List.of(),
                List.of(), List.of(new MethodCall(location(TEST_FILE, 6), "", "expectCreated", 0,
                        Optional.of(helper.id()), ResolutionReason.SAME_CLASS)));
        assertThat(new TestWithoutAssertionRule().evaluate(project(viaHelper, helper))).isEmpty();
    }

    @Test
    void testWithoutAssertion_skipsExpectedExceptionAndDisabledTests() {
        var expected = new MethodModel(new MethodId("example.OrderTest", "expected", List.of()),
                location(TEST_FILE, 3), Set.of("Test"), List.of(), List.of(invocation("service", "run")), List.of(),
                List.of(), List.of(), ProxyProfile.unknown(),
                Map.of("Test", Map.of("expected", "IllegalStateException.class")), CallableShape.fixed(0), "void",
                false);
        var disabled = test("disabled", Set.of("Test", "Disabled"), Map.of(), invocation("service", "run"));
        assertThat(new TestWithoutAssertionRule().evaluate(project(expected, disabled))).isEmpty();
    }

    // ── TEST_SWALLOWS_EXCEPTION ──────────────────────────────────────────────

    @Test
    void swallowsException_broadCatchWithoutFailureIsReported() {
        var swallow = new MethodModel(new MethodId("example.OrderTest", "swallow", List.of()), location(TEST_FILE, 3),
                Set.of("Test"), List.of(catchOf(8, "Exception", false)), List.of(invocation("service", "run")),
                List.of(), List.of(), List.of());
        var rethrow = new MethodModel(new MethodId("example.OrderTest", "rethrow", List.of()), location(TEST_FILE, 13),
                Set.of("Test"), List.of(catchOf(18, "Exception", true)), List.of(invocation("service", "run")),
                List.of(), List.of(), List.of());
        var specific = new MethodModel(new MethodId("example.OrderTest", "specific", List.of()),
                location(TEST_FILE, 23), Set.of("Test"), List.of(catchOf(28, "IllegalStateException", false)),
                List.of(invocation("service", "run")), List.of(), List.of(), List.of());
        assertThat(new TestSwallowsExceptionRule().evaluate(project(swallow, rethrow, specific)))
                .singleElement().satisfies(f -> assertThat(f.evidence())
                        .containsEntry("method", "example.OrderTest.swallow()")
                        .containsEntry("exceptionType", "Exception"));
    }

    @Test
    void swallowsException_assertionAfterTheCatchIsAccepted() {
        var checked = new MethodModel(new MethodId("example.OrderTest", "checked", List.of()), location(TEST_FILE, 3),
                Set.of("Test"), List.of(catchOf(8, "Exception", false)),
                List.of(invocation("service", "run"), invocationAt(10, "", "fail")), List.of(), List.of(), List.of());
        assertThat(new TestSwallowsExceptionRule().evaluate(project(checked))).isEmpty();
    }

    // ── TEST_DISABLED_WITHOUT_REASON ─────────────────────────────────────────

    @Test
    void disabled_bareAnnotationIsReported_reasonIsNot() {
        var bare = test("bare", Set.of("Test", "Disabled"), Map.of("Disabled", Map.of()));
        var reasoned = test("reasoned", Set.of("Test", "Disabled"), Map.of("Disabled", Map.of("value", "\"PAY-1\"")));
        assertThat(new TestDisabledWithoutReasonRule().evaluate(project(bare, reasoned)))
                .singleElement().satisfies(f -> assertThat(f.evidence())
                        .containsEntry("method", "example.OrderTest.bare()"));
    }

    @Test
    void disabled_aWholeClassIsReportedOnce() {
        var first = test("first", Set.of("Test", "Disabled"), Map.of("Disabled", Map.of()));
        var second = test("second", Set.of("Test", "Disabled"), Map.of("Disabled", Map.of()));
        assertThat(new TestDisabledWithoutReasonRule().evaluate(project(first, second)))
                .singleElement().satisfies(f -> assertThat(f.evidence())
                        .containsEntry("declaringType", "example.OrderTest"));
    }

    // ── TEST_THREAD_SLEEP / TEST_BROAD_EXPECTED_EXCEPTION ────────────────────

    @Test
    void threadSleep_isReportedInTestsOnly() {
        var sleeping = test("sleeping", invocation("Thread", "sleep"));
        var helper = method("example.OrderTest", "helper", TEST_FILE, Set.of(), invocation("Thread", "sleep"));
        assertThat(new TestThreadSleepRule().evaluate(project(sleeping, helper))).singleElement()
                .satisfies(f -> assertThat(f.evidence()).containsEntry("method", "example.OrderTest.sleeping()"));
    }

    @Test
    void broadExpectedException_isReported_specificIsNot() {
        var broad = test("broad", new InvocationEvidence(location(TEST_FILE, 5), "", "", "assertThrows",
                List.of("Exception.class", "() -> run()"), InvocationResultUsage.IGNORED));
        var specific = test("specific", new InvocationEvidence(location(TEST_FILE, 9), "", "", "assertThrows",
                List.of("IllegalStateException.class", "() -> run()"), InvocationResultUsage.IGNORED));
        var annotated = new MethodModel(new MethodId("example.OrderTest", "annotated", List.of()),
                location(TEST_FILE, 12), Set.of("Test"), List.of(), List.of(), List.of(), List.of(), List.of(),
                ProxyProfile.unknown(), Map.of("Test", Map.of("expected", "Exception.class")),
                CallableShape.fixed(0), "void", false);
        assertThat(new TestBroadExpectedExceptionRule().evaluate(project(broad, specific, annotated)))
                .extracting(f -> f.evidence().get("method"))
                .containsExactlyInAnyOrder("example.OrderTest.broad()", "example.OrderTest.annotated()");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static MethodModel test(String name, InvocationEvidence... invocations) {
        return test(name, Set.of("Test"), Map.of(), invocations);
    }

    private static MethodModel test(String name, Set<String> annotations, Map<String, Map<String, String>> attributes,
                                    InvocationEvidence... invocations) {
        return new MethodModel(new MethodId("example.OrderTest", name, List.of()), location(TEST_FILE, 3),
                annotations, List.of(), List.of(invocations), List.of(), List.of(), List.of(),
                ProxyProfile.unknown(), attributes, CallableShape.fixed(0), "void", false);
    }

    private static MethodModel withoutTestAnnotation(MethodModel method) {
        return method("example.OrderTest", method.id().name(), TEST_FILE, Set.of(),
                method.invocations().toArray(InvocationEvidence[]::new));
    }

    private static MethodModel method(String type, String name, String file, Set<String> annotations,
                                      InvocationEvidence... invocations) {
        return new MethodModel(new MethodId(type, name, List.of()), location(file, 3), annotations, List.of(),
                List.of(invocations), List.of(), List.of(), List.of());
    }

    private static CatchEvidence catchOf(int line, String type, boolean rethrows) {
        return new CatchEvidence(location(TEST_FILE, line), type, !rethrows, false, rethrows, false, "");
    }

    private static InvocationEvidence invocation(String name) {
        return invocationAt(5, "", name);
    }

    private static InvocationEvidence invocation(String scope, String name) {
        return invocationAt(5, scope, name);
    }

    private static InvocationEvidence invocationAt(int line, String scope, String name) {
        return new InvocationEvidence(location(TEST_FILE, line), scope, scope, name, List.of(),
                InvocationResultUsage.IGNORED);
    }

    private static AnalyzedProject project(MethodModel... methods) {
        var map = new LinkedHashMap<MethodId, MethodModel>();
        for (var method : methods) map.put(method.id(), method);
        var root = Path.of(".");
        return new AnalyzedProject("test", root,
                new ProjectLayout(BuildSystem.MAVEN, root, List.of(), List.of(root)), map, List.of(), 1L, List.of());
    }

    private static SourceLocation location(String file, int line) {
        return new SourceLocation(Path.of(file), line, line);
    }
}
