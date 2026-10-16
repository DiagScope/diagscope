package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code new RestTemplate()} whose error handler is never configured.
 *
 * <p>The default handler throws {@code HttpClientErrorException} for every 4xx and
 * {@code HttpServerErrorException} for every 5xx. That is a valid choice, but when it is accepted by omission
 * the exceptions usually surface as generic 500s with the downstream body lost, and 404s that are an expected
 * answer become failures. Setting a handler (or a status-aware wrapper) makes the policy explicit.</p>
 *
 * <p>Only a {@code RestTemplate} constructed in the method is considered; an injected one is not visible.
 * A {@code setErrorHandler}/{@code errorHandler} call in the same method counts as configured.</p>
 */
public final class HttpClientNoErrorHandlerRule implements ProjectRule {

    public static final String ID = "HTTP_CLIENT_NO_ERROR_HANDLER";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (RuleSupport.isTestCode(method)) continue;
            if (RuleSupport.calls(method, "setErrorHandler", "errorHandler")) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                if (!RuleSupport.isConstructorOf(invocation, "RestTemplate")) continue;
                findings.add(new Finding(
                        ID, Severity.INFO, Confidence.MEDIUM, invocation.location(),
                        "'" + method.id().displayName() + "' creates a RestTemplate without an error handler. The default one turns"
                                + " every 4xx/5xx into an exception, usually surfacing as a generic 500 with the downstream response"
                                + " body lost.",
                        "Call setErrorHandler(...) (or build it with RestTemplateBuilder.errorHandler) so the policy is explicit:"
                                + " map expected statuses such as 404 to a result and wrap the rest in an exception that keeps the"
                                + " status and body.",
                        List.of(),
                        Map.of("method", method.id().displayName())));
            }
        }
        return List.copyOf(findings);
    }
}
