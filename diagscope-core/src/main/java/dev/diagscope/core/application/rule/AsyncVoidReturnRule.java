package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reports {@code @Async} methods that return {@code void} instead of
 * {@code Future} or {@code CompletableFuture}.
 *
 * <p><b>Why it matters:</b> When an {@code @Async void} method throws an exception, Spring does
 * not propagate it to the caller — the caller has already returned. Instead, the exception is
 * passed to the thread's {@code AsyncUncaughtExceptionHandler}. Spring ships a default handler
 * ({@code SimpleAsyncUncaughtExceptionHandler}) that logs the exception at ERROR level and
 * silently discards it. The vast majority of teams never configure a custom handler, which means
 * every exception thrown by every {@code @Async void} method is silently swallowed after a log
 * line that is easy to miss.</p>
 *
 * <p>If the return type is {@code CompletableFuture} or {@code Future}, the caller receives a
 * future that is completed exceptionally, and the exception can be observed with
 * {@code .exceptionally()} or surfaced when the future is joined. This makes failure
 * visible and actionable.</p>
 */
public final class AsyncVoidReturnRule implements ProjectRule {

    public static final String ID = "ASYNC_VOID_RETURN";

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
        if (!method.annotations().contains("Async")) return;

        String rt = method.returnType();
        boolean isVoid = rt == null || rt.isBlank() || "void".equalsIgnoreCase(rt) || "Unit".equals(rt);
        if (!isVoid) return;

        String display = method.id().displayName();
        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.HIGH, method.location(),
                "'" + display + "' is @Async with a void return type. Exceptions thrown"
                        + " inside this method are passed to AsyncUncaughtExceptionHandler,"
                        + " which by default logs once and discards silently. The caller"
                        + " receives no signal that the operation failed.",
                "Return CompletableFuture<Void> and wrap the method body with"
                        + " CompletableFuture.runAsync(() -> { ... }, executor) or"
                        + " return CompletableFuture.completedFuture(null) at the end."
                        + " If void return is intentional, configure a custom"
                        + " AsyncUncaughtExceptionHandler bean that alerts or records the failure.",
                List.of(),
                Map.of(
                        "method", display,
                        "declaringType", method.id().declaringType()
                )));
    }
}
