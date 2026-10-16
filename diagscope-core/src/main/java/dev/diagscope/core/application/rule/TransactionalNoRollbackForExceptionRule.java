package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reports {@code @Transactional(noRollbackFor = Exception.class)} and its broad variants.
 *
 * <p>{@code noRollbackFor} names exceptions that must <em>not</em> roll the transaction back. Naming
 * {@code Exception}, {@code Throwable} or {@code RuntimeException} covers practically every failure, so a
 * database error or a bug midway through still commits the writes made so far, leaving partial data that no
 * log line explains. Jakarta's {@code @Transactional(dontRollbackOn = ...)} is treated the same way.</p>
 *
 * <p>The annotation may sit on the class, in which case every method inherits it; the finding is reported
 * once per declaring type and value.</p>
 */
public final class TransactionalNoRollbackForExceptionRule implements ProjectRule {

    public static final String ID = "TRANSACTIONAL_NO_ROLLBACK_FOR_EXCEPTION";

    private static final Pattern BROAD = Pattern.compile(
            "(?<![\\w.])(?:java\\.lang\\.)?(Exception|Throwable|RuntimeException)\\s*(?:\\.class|::class)");
    private static final Set<String> ATTRIBUTES = Set.of("noRollbackFor", "noRollbackForClassName", "dontRollbackOn");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        var reported = new java.util.HashSet<String>();
        for (MethodModel method : project.methods().values()) {
            if (!DiagnosticSignals.hasAnnotation(method, "Transactional")) continue;
            for (String attribute : ATTRIBUTES) {
                String value = method.annotationAttribute("Transactional", attribute).orElse("");
                var matcher = BROAD.matcher(value);
                if (!matcher.find()) continue;
                if (!reported.add(method.id().declaringType() + '|' + attribute + '|' + value)) continue;
                String broad = matcher.group(1);
                findings.add(new Finding(
                        ID, Severity.ERROR, Confidence.HIGH, method.location(),
                        "'" + method.id().displayName() + "' is @Transactional with " + attribute + " = " + value.strip()
                                + ". Naming " + broad + " disables rollback for almost every failure, so a database error or a"
                                + " bug midway through still commits the writes made so far.",
                        "Remove the attribute, or list only the specific business exceptions that are an expected outcome"
                                + " and should still commit. Never name Exception, Throwable or RuntimeException.",
                        List.of(),
                        Map.of("declaringType", method.id().declaringType(), "attribute", attribute, "exception", broad)));
            }
        }
        return List.copyOf(findings);
    }
}
