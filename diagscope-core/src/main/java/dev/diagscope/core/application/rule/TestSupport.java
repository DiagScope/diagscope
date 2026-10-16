package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Facts the test-source rules share. */
final class TestSupport {
    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory");

    /** Names that check a result, in JUnit, AssertJ, Mockito, Hamcrest, MockMvc, WebTestClient, Reactor and Kotest. */
    private static final Pattern ASSERTION_NAME = Pattern.compile(
            "(?i)^(assert\\w*|verify\\w*|fail|expect\\w*|andExpect|andExpectAll|should\\w*|check\\w*"
                    + "|isEqualTo|isTrue|isFalse|isNull|isNotNull|isInstanceOf|thenThrownBy|consumeWith|verifyComplete"
                    + "|verifyError|verifyThenAssertThat|withClue|assertSoftly"
                    // Vert.x JUnit 5: a test passes by completing its context, and fails on failNow or timeout.
                    + "|completeNow|failNow|succeeding|succeedingThenComplete|failing|flag|checkpoint|strictCheckpoint"
                    + "|awaitCompletion"
                    // Awaitility polls until a condition holds and fails the test when it never does.
                    + "|await|until|untilAsserted|untilTrue|untilFalse|untilNotNull)$");

    private TestSupport() {
    }

    static boolean isTestMethod(MethodModel method) {
        return method.annotations().stream().map(ExecutionContexts::simpleAnnotationName)
                .anyMatch(TEST_ANNOTATIONS::contains);
    }

    static boolean isAssertionCall(InvocationEvidence invocation) {
        return ASSERTION_NAME.matcher(invocation.methodName()).matches();
    }

    /** Whether the method, or a project method it calls (two levels deep), checks something. */
    static boolean assertsSomething(MethodModel method, AnalyzedProject project) {
        return assertsSomething(method, project, new HashSet<>(), 2);
    }

    private static boolean assertsSomething(MethodModel method, AnalyzedProject project, Set<MethodId> seen,
                                            int depth) {
        if (!seen.add(method.id())) return false;
        if (method.invocations().stream().anyMatch(TestSupport::isAssertionCall)) return true;
        if (depth == 0) return false;
        for (var call : method.calls()) {
            if (call.target().isEmpty()) continue;
            MethodModel callee = project.methods().get(call.target().get());
            if (callee != null && assertsSomething(callee, project, seen, depth - 1)) return true;
        }
        return false;
    }

    static boolean isThreadSleep(InvocationEvidence invocation) {
        if (!invocation.methodName().equals("sleep")) return false;
        String hint = RuleSupport.hintText(invocation).toLowerCase(Locale.ROOT);
        return hint.contains("thread") || hint.contains("timeunit") || hint.matches(".*\\b(seconds|millis|minutes)\\b.*");
    }
}
