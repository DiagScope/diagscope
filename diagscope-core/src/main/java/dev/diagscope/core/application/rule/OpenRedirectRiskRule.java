package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reports a REST handler that redirects to a caller-controlled location.
 *
 * <p>{@code response.sendRedirect(target)} and {@code new RedirectView(target)} with a non-constant
 * {@code target} let an attacker craft a link on your domain that bounces the victim to a phishing site. The
 * user trusts the link because it starts with your host.</p>
 *
 * <p>A redirect is not reported when its argument is built only from literals and constants, or when the
 * method visibly validates the target ({@code startsWith}, {@code matches}, {@code contains}, an
 * {@code isAllowed}/{@code isValid}/{@code isSafe} check). Validation that happens in another method is not
 * seen. Confidence is HIGH when the target names a method parameter or the method reads the request object, MEDIUM
 * otherwise.</p>
 */
public final class OpenRedirectRiskRule implements ProjectRule {

    public static final String ID = "OPEN_REDIRECT_RISK";

    private static final Set<String> VALIDATION_CALLS = Set.of(
            "startsWith", "matches", "contains", "isAllowed", "isValid", "isSafe", "isTrusted", "validate", "isRelative");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (!RuleSupport.isRestEndpoint(method) || RuleSupport.calls(method, VALIDATION_CALLS.toArray(String[]::new))) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                boolean redirect = invocation.methodName().equals("sendRedirect")
                        || RuleSupport.isConstructorOf(invocation, "RedirectView");
                if (!redirect || invocation.arguments().isEmpty()) continue;
                String target = invocation.arguments().get(0);
                if (!RuleSupport.isDynamicExpression(target)) continue;
                boolean requestData = RuleSupport.carriesRequestData(method, target);
                findings.add(new Finding(
                        ID, Severity.ERROR, requestData ? Confidence.HIGH : Confidence.MEDIUM, invocation.location(),
                        "'" + method.id().displayName() + "' redirects to '" + target.strip() + "', which is not a constant."
                                + " If any part comes from the request, an attacker can send users to an arbitrary site"
                                + " through a link on your domain.",
                        "Redirect only to a fixed path or to a value chosen from an allowlist. For return-to URLs accept"
                                + " a relative path, reject anything containing a scheme or '//', and compare the host"
                                + " against the configured one before redirecting.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "call", invocation.methodName())));
            }
        }
        return List.copyOf(findings);
    }
}
