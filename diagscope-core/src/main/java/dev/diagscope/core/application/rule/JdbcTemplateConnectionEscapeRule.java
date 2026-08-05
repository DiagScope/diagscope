package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reports code that reaches the raw {@code Connection} behind a {@code JdbcTemplate} or
 * {@code DataSourceUtils} and manages it by hand.
 *
 * <p>{@code JdbcTemplate} exists to bind the connection to the active transaction and to translate
 * driver exceptions into the Spring hierarchy. Taking the connection out of it produces a handle that
 * is outside the current transaction, whose failures arrive as raw {@code SQLException}, and whose
 * release is now the caller's job — usually a job the success path does and the failure path forgets.
 * </p>
 */
public final class JdbcTemplateConnectionEscapeRule implements DiagnosticRule {
    public static final String ID = "JDBC_TEMPLATE_CONNECTION_ESCAPE";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            MethodModel method = flowMethod.method();
            for (InvocationEvidence invocation : method.invocations()) {
                if (!DatabaseResources.escapesJdbcTemplate(invocation)) {
                    continue;
                }
                DatabaseResources.Release release =
                        DatabaseResources.releaseOf(method, invocation.assignedTo());
                if (invocation.resourceManaged() && release == DatabaseResources.Release.NONE) {
                    // try-with-resources closes it, but it is still detached from the template's transaction.
                    release = DatabaseResources.Release.GUARDED;
                }

                Confidence ruleConfidence = switch (release) {
                    case NONE -> Confidence.HIGH;
                    case HAPPY_PATH_ONLY -> Confidence.HIGH;
                    case GUARDED -> Confidence.MEDIUM;
                };
                Confidence confidence = Confidence.min(ruleConfidence, flowMethod.confidence());
                var details = new LinkedHashMap<String, String>();
                details.put("method", method.id().displayName());
                details.put("receiver", invocation.scope());
                details.put("assignedTo", invocation.assignedTo());
                details.put("release", switch (release) {
                    case NONE -> "never released in this method";
                    case HAPPY_PATH_ONLY -> "released on the success path only";
                    case GUARDED -> "released on every path";
                });
                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "Raw JDBC connection taken from " + describe(invocation)
                                + " bypasses the template's transaction binding and exception translation.",
                        "Run the statement through JdbcTemplate (execute, query, update) so the connection"
                                + " stays bound to the active transaction, or release it with"
                                + " DataSourceUtils.releaseConnection in a finally block.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        details));
            }
        }
        return List.copyOf(findings);
    }

    private static String describe(InvocationEvidence invocation) {
        String scope = invocation.scope();
        return scope.isBlank() ? "the configured DataSource" : scope;
    }
}
