package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reports log statements that print the value of a credential-bearing HTTP header.
 *
 * <p>{@code Authorization}, {@code Cookie}, {@code Set-Cookie}, {@code X-API-Key} and similar headers carry
 * bearer tokens, session ids and API keys. Once logged they live in log storage, backups and third-party
 * aggregators with a far wider audience and longer retention than the credential was meant to have.</p>
 *
 * <p>Only a header <em>read</em> inside a log argument counts — {@code getHeader("Authorization")},
 * {@code headers.get("Cookie")}, {@code headers["X-API-Key"]}. A message that merely mentions the word
 * ("Missing Authorization header") is not reported. Logging a whole header map is not detected.</p>
 */
public final class SensitiveHeaderLoggedRule implements ProjectRule {

    public static final String ID = "SENSITIVE_HEADER_LOGGED";

    private static final String NAMES =
            "authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-auth-token|x-csrf-token";
    private static final Pattern HEADER_READ = Pattern.compile(
            "(?i)(?:getHeader|getHeaders|getFirst|getFirstHeader|header|headers?\\s*\\.\\s*(?:get|first|getFirst))"
                    + "\\s*\\(\\s*\"(" + NAMES + ")\"|\\[\\s*\"(" + NAMES + ")\"\\s*]");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (RuleSupport.isTestCode(method)) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                if (!DiagnosticSignals.isLoggerCall(invocation)) continue;
                String header = loggedHeader(invocation);
                if (header == null) continue;
                findings.add(new Finding(
                        ID, Severity.ERROR, Confidence.HIGH, invocation.location(),
                        "'" + method.id().displayName() + "' logs the value of the '" + header + "' header. Credentials"
                                + " written to logs are readable by everyone with log access and persist in backups and"
                                + " log aggregators.",
                        "Do not log the header value. If you need to correlate requests, log a request id or a masked"
                                + " fingerprint (for example the last 4 characters), and configure the HTTP client or"
                                + " server access log to redact this header.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "header", header.toLowerCase(Locale.ROOT))));
            }
        }
        return List.copyOf(findings);
    }

    private static String loggedHeader(InvocationEvidence invocation) {
        for (String argument : invocation.arguments()) {
            var matcher = HEADER_READ.matcher(argument);
            if (matcher.find()) return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        }
        return null;
    }
}
