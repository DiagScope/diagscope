package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Small predicates shared by the syntax-level rules, so each rule only states what is specific to it. */
final class RuleSupport {

    static final Set<String> SPRING_STEREOTYPES = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController",
            "Configuration", "ControllerAdvice", "RestControllerAdvice");

    private static final Set<String> REST_MAPPINGS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping",
            "GET", "POST", "PUT", "DELETE", "PATCH");

    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Test", "ParameterizedTest", "BeforeEach", "BeforeAll", "AfterEach", "AfterAll", "Before", "After",
            "TestConfiguration");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
    private static final Pattern CONSTANT_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    private RuleSupport() {
    }

    static boolean isSpringBean(MethodModel method) {
        return method.annotations().stream().anyMatch(SPRING_STEREOTYPES::contains);
    }

    static boolean isRestEndpoint(MethodModel method) {
        return method.annotations().stream().map(ExecutionContexts::simpleAnnotationName)
                .anyMatch(REST_MAPPINGS::contains);
    }

    static boolean isTestCode(MethodModel method) {
        return method.annotations().stream().map(ExecutionContexts::simpleAnnotationName)
                .anyMatch(TEST_ANNOTATIONS::contains);
    }

    /** Factory and lifecycle methods that legitimately construct infrastructure objects. */
    static boolean isFactoryMethod(MethodModel method) {
        return DiagnosticSignals.hasAnnotation(method, "Bean") || DiagnosticSignals.hasAnnotation(method, "PostConstruct")
                || isTestCode(method);
    }

    /** {@code new Type(...)} (Java) or {@code Type(...)} (Kotlin): no receiver, the type is the method name. */
    static boolean isConstructorOf(InvocationEvidence invocation, String type) {
        return invocation.methodName().equals(type) && invocation.scope().isBlank()
                && invocation.receiverType().isBlank();
    }

    /** Whether the call is made on a receiver whose simple type, or whose scope text, is {@code type}. */
    static boolean isCallOn(InvocationEvidence invocation, String type) {
        String receiver = ExecutionContexts.simpleTypeName(invocation.receiverType());
        if (receiver.equals(type)) return true;
        String scope = invocation.scope().strip();
        return scope.equals(type) || scope.endsWith("." + type);
    }

    /** Lower-cased scope and receiver type, for naming-hint checks. */
    static String hint(InvocationEvidence invocation) {
        return (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
    }

    static boolean calls(MethodModel method, String... names) {
        for (InvocationEvidence invocation : method.invocations()) {
            for (String name : names) {
                if (invocation.methodName().equals(name)) return true;
            }
        }
        return false;
    }

    /**
     * Whether an argument expression can carry caller-controlled data: it is neither a plain literal nor
     * built only from literals and upper-case constants.
     */
    static boolean isDynamicExpression(String expression) {
        if (expression == null) return false;
        String rest = STRING_LITERAL.matcher(expression).replaceAll(" ")
                .replaceAll("[+\\s()\\[\\]{}.,$]|\\bnew\\b|\\bString\\b|\\btoString\\b|\\bgetContextPath\\b", " ");
        for (String token : rest.split("\\s+")) {
            if (token.isBlank() || token.matches("\\d+[LlFfDd]?")) continue;
            if (!CONSTANT_NAME.matcher(token).matches()) return true;
        }
        return false;
    }

    static String simpleName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }

    /** Whether a declared return type is {@code void}/{@code Unit}. */
    static boolean returnsNothing(MethodModel method) {
        String type = method.returnType().strip();
        return type.equals("void") || type.equals("Unit");
    }
}
