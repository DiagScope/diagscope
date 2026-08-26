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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reports blocking JVM calls found inside coroutine builder lambdas ({@code launch},
 * {@code async}, {@code runBlocking}).
 *
 * <p>Kotlin's coroutine dispatchers multiplex many coroutines onto a small pool of threads.
 * The default dispatcher ({@code Dispatchers.Default}) sizes its pool to the number of CPU
 * cores. If a coroutine blocks one of those threads with a blocking call — {@code Thread.sleep},
 * {@code Object.wait}, synchronous IO — the thread is unavailable for the entire duration of
 * the block. Under load, even a single blocking call can starve the dispatcher: all other
 * coroutines queue behind it, producing latency spikes that look like a performance regression
 * rather than a concurrency bug.</p>
 *
 * <p>The fix is always to wrap blocking calls in {@code withContext(Dispatchers.IO) { … }},
 * which offloads them to an unbounded IO thread pool designed for exactly this purpose.</p>
 *
 * <p>Detection strategy: the Kotlin parser captures lambda bodies as argument text on the
 * outer call expression. This rule inspects the text of each lambda argument on {@code launch},
 * {@code async}, and {@code runBlocking} invocations for well-known blocking patterns.
 * A finding is suppressed when the lambda argument text already contains
 * {@code withContext(Dispatchers.IO} or {@code withContext(Dispatchers.Default}, indicating
 * the developer intentionally switched to a blocking-friendly dispatcher.</p>
 *
 * <p>Known limitations: deeply nested {@code withContext} wrappers inside complex lambdas may
 * not be reliably detected by the suppression heuristic, leading to false positives in those
 * cases. The rule also cannot analyse blocking calls hidden inside helper functions called
 * from the lambda — those would require inter-procedural analysis.</p>
 */
public final class BlockingCallInCoroutineRule implements ProjectRule {

    public static final String ID = "BLOCKING_CALL_IN_COROUTINE";

    // Coroutine builder functions this rule monitors
    private static final Set<String> BUILDER_METHODS = Set.of("launch", "async", "runBlocking");

    // Text patterns that signal a blocking JVM call inside the lambda body
    // We use simple contains() checks to stay fast; confidence is MEDIUM for this reason.
    private static final List<String> BLOCKING_PATTERNS = List.of(
            "Thread.sleep(",
            "Thread.currentThread().join(",
            ".wait(",
            "Object.wait(",
            // Synchronous HTTP — java.net.HttpURLConnection, OkHttp Call.execute()
            ".openConnection()",
            "HttpURLConnection",
            "URLConnection",
            // Blocking future resolution inside coroutines
            ".get()",      // CompletableFuture.get()
            ".join()"      // CompletableFuture.join()
    );

    // If any of these are present in the lambda body, the developer already switched to
    // a blocking-safe dispatcher — suppress the finding.
    private static final Pattern SAFE_CONTEXT = Pattern.compile(
            "withContext\\s*\\(\\s*Dispatchers\\.(IO|Default)");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (var method : project.methods().values()) {
            for (var inv : method.invocations()) {
                checkBuilder(method, inv, findings);
            }
        }
        return List.copyOf(findings);
    }

    private static void checkBuilder(MethodModel method, InvocationEvidence inv, List<Finding> findings) {
        if (!BUILDER_METHODS.contains(inv.methodName())) return;

        // Find the lambda body argument(s) — they start with '{'
        for (String arg : inv.arguments()) {
            String argTrimmed = arg.strip();
            if (!argTrimmed.startsWith("{")) continue;

            // Suppress if the lambda already wraps in an IO/Default context switch
            if (SAFE_CONTEXT.matcher(argTrimmed).find()) continue;

            // Look for blocking call patterns
            String matched = findBlockingPattern(argTrimmed);
            if (matched == null) continue;

            String scopeLabel = inv.scope().isBlank()
                    ? inv.methodName() + " {}"
                    : inv.scope() + "." + inv.methodName() + " {}";

            findings.add(new Finding(
                    ID, Severity.ERROR, Confidence.MEDIUM, inv.location(),
                    "Blocking call '" + matched.strip() + "' detected inside a '"
                            + inv.methodName() + "' coroutine lambda ("
                            + scopeLabel + "). This blocks the coroutine dispatcher thread"
                            + " and can starve other coroutines under concurrency.",
                    "Wrap the blocking call in withContext(Dispatchers.IO) { … } to offload"
                            + " it to the IO thread pool, which is designed for blocking operations."
                            + " Example: withContext(Dispatchers.IO) { Thread.sleep(delay) }."
                            + " If the call is non-blocking (e.g., kotlinx.coroutines delay()),"
                            + " replace it with the suspending equivalent instead.",
                    List.of(),
                    Map.of(
                            "method", method.id().displayName(),
                            "coroutineBuilder", inv.methodName(),
                            "blockingPattern", matched.strip(),
                            "scope", inv.scope()
                    )));
        }
    }

    /** Returns the first blocking pattern found in the lambda text, or {@code null} if none. */
    private static String findBlockingPattern(String lambdaBody) {
        String lower = lambdaBody.toLowerCase(Locale.ROOT);
        for (String pattern : BLOCKING_PATTERNS) {
            if (lower.contains(pattern.toLowerCase(Locale.ROOT))) {
                return pattern;
            }
        }
        return null;
    }
}
