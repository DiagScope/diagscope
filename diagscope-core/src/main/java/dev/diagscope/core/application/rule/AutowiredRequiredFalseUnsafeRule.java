package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports a field injected with {@code @Autowired(required = false)} that a method uses without checking it for
 * {@code null}.
 *
 * <p>With {@code required = false} Spring leaves the field untouched when no bean matches, so the field stays
 * {@code null}. Calling a method on it then fails with a {@code NullPointerException} that points at the call, not
 * at the missing bean. The parsers record the optional fields of a class and the variables each method compares with
 * {@code null}; the rule reports the first call on such a field in a method that has no such comparison. Fields typed
 * {@code Optional}, {@code ObjectProvider} or {@code Provider} already model absence and are never recorded, and
 * Kotlin properties declared nullable are left to the compiler.</p>
 */
public final class AutowiredRequiredFalseUnsafeRule implements DiagnosticRule {
    public static final String ID = "AUTOWIRED_REQUIRED_FALSE_UNSAFE";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            MethodModel method = flowMethod.method();
            Set<String> optionalFields = optionalFields(method);
            if (optionalFields.isEmpty()) continue;
            Set<String> checked = RuleSupport.nullCheckedNames(method);
            var reported = new java.util.HashSet<String>();
            for (var invocation : method.invocations()) {
                String field = invocation.scopeRoot();
                if (field.equals("this")) {
                    field = invocation.scope().replaceFirst("^this\\.", "");
                    field = field.substring(0, identifierEnd(field));
                }
                if (!optionalFields.contains(field) || checked.contains(field) || !reported.add(field)) continue;

                var confidence = Confidence.min(Confidence.MEDIUM, flowMethod.confidence());
                var evidence = new LinkedHashMap<String, String>();
                evidence.put("method", method.id().displayName());
                evidence.put("field", field);
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "'" + field + "' is injected with @Autowired(required = false) and '"
                                + invocation.methodName() + "' is called on it without a null check. When no bean"
                                + " matches, the field stays null and this call throws a NullPointerException.",
                        "Check the field for null before use, inject Optional<T> or ObjectProvider<T> instead,"
                                + " or drop required = false if the dependency is not really optional.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        evidence));
            }
        }
        return List.copyOf(findings);
    }

    private static Set<String> optionalFields(MethodModel method) {
        String fields = method.annotationAttribute(MethodModel.OPTIONAL_AUTOWIRED_ANNOTATION, "fields").orElse("");
        return fields.isBlank() ? Set.of() : Set.of(fields.split(","));
    }

    private static int identifierEnd(String text) {
        int end = 0;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) end++;
        return end;
    }
}
