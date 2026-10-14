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
 * Reports a {@code synchronized} method declared on a class that is also annotated with
 * {@code @RunOnVirtualThread} (Quarkus) or any equivalent annotation that signals virtual-thread
 * execution.
 *
 * <p><b>Why it matters:</b> Virtual threads (Java 21+) are designed to be lightweight and
 * non-blocking. However, when a virtual thread encounters a {@code synchronized} block or method,
 * it <em>pins</em> the underlying carrier thread for the entire duration of the synchronized section.
 * Pinning prevents the JVM from unmounting the virtual thread and parking the carrier thread while
 * waiting — the carrier is blocked just like a platform thread would be. Under any meaningful
 * concurrency, this defeats the purpose of virtual threads entirely: if all virtual threads
 * frequently pin their carriers, the effective thread pool becomes the carrier pool, which is
 * typically sized to the number of CPU cores.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Find methods whose effective annotation set contains {@code "RunOnVirtualThread"} or
 *       {@code "StartVirtualThread"} (the class-level annotation is merged onto every method by
 *       both parsers).</li>
 *   <li>Check if the same method also carries the synthetic {@code "Synchronized"} annotation
 *       (synthesised from the {@code synchronized} Java keyword or the Kotlin
 *       {@code @Synchronized} annotation by both parsers).</li>
 *   <li>Emit ERROR with HIGH confidence — both signals are read directly from the AST.</li>
 * </ol>
 *
 * <p><b>Fix:</b> Replace {@code synchronized} with {@code java.util.concurrent.locks.ReentrantLock}
 * using try-finally, or with lock-free data structures such as {@code ConcurrentHashMap},
 * {@code AtomicReference}, or {@code AtomicLong}. These do not pin the carrier thread.</p>
 */
public final class VirtualThreadWithSynchronizedRule implements ProjectRule {

    public static final String ID = "VIRTUAL_THREAD_WITH_SYNCHRONIZED";

    private static final Set<String> VIRTUAL_THREAD_ANNOTATIONS = Set.of(
            "RunOnVirtualThread",
            "StartVirtualThread"
    );

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
        boolean isVirtualThread = method.annotations().stream()
                .anyMatch(VIRTUAL_THREAD_ANNOTATIONS::contains);
        if (!isVirtualThread) return;

        if (!method.annotations().contains("Synchronized")) return;

        String display = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.ERROR, Confidence.HIGH, method.location(),
                "'" + display + "' is marked @RunOnVirtualThread and uses synchronized."
                        + " Synchronized blocks pin the carrier thread, which prevents the JVM from"
                        + " unmounting the virtual thread and defeats the scalability benefit of"
                        + " virtual threads under concurrency.",
                "Replace 'synchronized' with a java.util.concurrent.locks.ReentrantLock using"
                        + " try-finally, or redesign to use lock-free structures"
                        + " (ConcurrentHashMap, AtomicReference, AtomicLong). These mechanisms"
                        + " do not pin the carrier thread and preserve virtual-thread scalability.",
                List.of(),
                Map.of(
                        "method", display,
                        "declaringType", method.id().declaringType()
                )));
    }
}
