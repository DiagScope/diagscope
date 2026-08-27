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

/**
 * Reports {@code @Transactional} or {@code @Async} methods that are also effectively
 * {@code final} (Java {@code final} keyword; Kotlin methods without {@code open}).
 *
 * <p><b>Why it matters:</b> Spring's AOP proxy mechanism works by creating a CGLIB subclass
 * of the bean's class. A {@code final} method cannot be overridden in a subclass, so the
 * proxy cannot intercept it. The result is that {@code @Transactional} is silently ignored —
 * the method runs without a transaction boundary, writes are auto-committed, and rollback
 * has no effect. {@code @Async} is similarly ignored — the method runs synchronously on the
 * calling thread while the caller believes the work was dispatched to a thread pool.</p>
 *
 * <p><b>Why Kotlin is especially prone to this:</b> In Kotlin, every method is {@code final}
 * by default unless declared {@code open}. A developer new to Spring who writes a
 * {@code @Transactional} Kotlin method and forgets {@code open} (or does not use the
 * {@code kotlin-spring} compiler plugin that auto-opens Spring-annotated classes) will
 * get a silently non-functional annotation. The {@code kotlin-spring} plugin is recommended
 * and widely used, but it must be explicitly configured.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Both parsers synthesise a {@code "Final"} {@link dev.diagscope.core.domain.AnnotationDescriptor}
 *       for the Java {@code final} modifier and for Kotlin methods that lack
 *       {@code open}, {@code abstract}, or {@code override}.</li>
 *   <li>This rule checks the effective annotation set for {@code "Final"} AND
 *       ({@code "Transactional"} OR {@code "Async"}).</li>
 *   <li>Emit ERROR — the annotation is guaranteed to be non-functional.</li>
 * </ol>
 *
 * <p><b>Fix (Java):</b> Remove the {@code final} modifier from the method. Spring beans
 * should not be final unless you are deliberately opting out of proxy-based AOP.</p>
 *
 * <p><b>Fix (Kotlin):</b> Either add {@code open} to the method, or add the
 * {@code kotlin-spring} compiler plugin which automatically opens all Spring-annotated
 * classes and methods:</p>
 * <pre>
 * // build.gradle.kts
 * plugins {
 *     kotlin("plugin.spring") version "..."
 * }
 * </pre>
 */
public final class TransactionalOnFinalMethodRule implements ProjectRule {

    public static final String ID = "TRANSACTIONAL_ON_FINAL_METHOD";

    private static final Set<String> PROXY_ANNOTATIONS = Set.of("Transactional", "Async");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            check(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, List<Finding> findings) {
        if (!method.annotations().contains("Final")) return;

        // Find which proxy annotations are present
        List<String> proxyAnnotationsPresent = PROXY_ANNOTATIONS.stream()
                .filter(method.annotations()::contains)
                .toList();
        if (proxyAnnotationsPresent.isEmpty()) return;

        String methodDisplay = method.id().displayName();
        String annotations = String.join(", @", proxyAnnotationsPresent);
        findings.add(new Finding(
                ID, Severity.ERROR, Confidence.HIGH, method.location(),
                "'" + methodDisplay + "' is @" + annotations + " but is final. CGLIB cannot"
                        + " create a proxy subclass that overrides a final method, so the"
                        + " annotation is silently ignored at runtime.",
                proxyAnnotationsPresent.contains("Transactional")
                        ? "Remove the 'final' modifier (Java) or declare the method 'open'"
                                + " (Kotlin). For Kotlin, the 'kotlin-spring' compiler plugin"
                                + " (plugin.spring) automatically opens all Spring-annotated"
                                + " classes and methods — add it to build.gradle.kts to avoid"
                                + " this issue across the entire project."
                        : "Remove the 'final' modifier (Java) or declare the method 'open'"
                                + " (Kotlin). For @Async, consider also specifying a named"
                                + " executor via @Async(\"executorBeanName\").",
                List.of(),
                Map.of(
                        "method", methodDisplay,
                        "proxyAnnotations", annotations,
                        "isFinal", "true"
                )));
    }
}
