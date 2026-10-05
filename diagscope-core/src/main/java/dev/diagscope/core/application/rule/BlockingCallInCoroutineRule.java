package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;
import dev.diagscope.core.domain.SourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reports blocking calls that run on a kotlinx.coroutines dispatcher thread.
 *
 * <p>Coroutine dispatchers multiplex many coroutines onto a few threads; {@code Dispatchers.Default}
 * has one per CPU core. A blocking call parks a whole thread, so a handful of them under load starve
 * every other coroutine on the dispatcher, producing latency spikes that look like a performance
 * regression and leave thread dumps full of legitimate-looking stacks.</p>
 *
 * <p>A call is considered to run on a coroutine thread when it is (a) in the body of a
 * {@code suspend} function, or (b) inside the lambda of a coroutine builder ({@code launch},
 * {@code async}, {@code runBlocking}, {@code produce}, {@code actor}, {@code withContext}). The
 * innermost enclosing builder that names a dispatcher decides:</p>
 * <ul>
 *   <li>{@code Dispatchers.IO} or any custom dispatcher: the call is on a blocking-friendly pool and
 *       is not reported;</li>
 *   <li>{@code Dispatchers.Default}, {@code Main} or {@code Unconfined}: reported with high
 *       confidence — {@code Default} is sized to the CPU count and is not a blocking pool either;</li>
 *   <li>no dispatcher named: the caller's dispatcher is inherited and unknown, reported with medium
 *       confidence.</li>
 * </ul>
 *
 * <p>What counts as blocking is decided by {@link BlockingCalls}, shared with
 * {@link BlockingCallInReactiveContextRule}. Enclosure is derived from source line ranges, so two
 * calls on the very same line as a builder are treated as enclosed by it.</p>
 *
 * <p>Limitations: only the body of the suspend function or builder lambda itself is inspected; a
 * blocking call inside a plain helper that such a body calls is not followed.</p>
 */
public final class BlockingCallInCoroutineRule implements ProjectRule {

    public static final String ID = "BLOCKING_CALL_IN_COROUTINE";

    private static final Set<String> BUILDERS = Set.of(
            "launch", "async", "runBlocking", "produce", "actor", "withContext");

    private static final Pattern BLOCKING_FRIENDLY = Pattern.compile("Dispatchers\\s*\\.\\s*IO\\b");
    private static final Pattern CPU_OR_UI_BOUND = Pattern.compile(
            "Dispatchers\\s*\\.\\s*(Default|Main|Unconfined)\\b");
    private static final Pattern ANY_DISPATCHER_ARGUMENT = Pattern.compile("(?i)\\w*dispatcher\\w*");

    private enum Dispatch { BLOCKING_FRIENDLY, CPU_OR_UI_BOUND, INHERITED }

    private record Region(InvocationEvidence builder, Dispatch dispatch) {
        boolean encloses(InvocationEvidence inner) {
            SourceLocation region = builder.location();
            SourceLocation point = inner.location();
            return builder != inner
                    && region.file().equals(point.file())
                    && region.startLine() <= point.startLine()
                    && point.endLine() <= region.endLine();
        }

        int span() {
            return builder.location().endLine() - builder.location().startLine();
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (var method : project.methods().values()) {
            var declared = ExecutionContexts.declared(method);
            // An explicit worker, virtual-thread or event-loop declaration is handled elsewhere.
            if (declared.isPresent() && declared.get().context() != ExecutionContext.COROUTINE) continue;
            evaluate(method, declared.isPresent(), findings);
        }
        return List.copyOf(findings);
    }

    private static void evaluate(MethodModel method, boolean suspend, List<Finding> findings) {
        List<Region> regions = regionsOf(method);
        if (!suspend && regions.isEmpty()) return;

        for (InvocationEvidence invocation : method.invocations()) {
            var blocking = BlockingCalls.classify(invocation);
            if (blocking.isEmpty()) continue;

            Optional<Region> deciding = decidingRegion(regions, invocation);
            Dispatch dispatch = deciding.map(Region::dispatch).orElse(Dispatch.INHERITED);
            if (dispatch == Dispatch.BLOCKING_FRIENDLY) continue;
            Optional<Region> enclosing = innermostRegion(regions, invocation);
            if (!suspend && enclosing.isEmpty()) continue;

            var call = blocking.get();
            Confidence contextConfidence = dispatch == Dispatch.CPU_OR_UI_BOUND ? Confidence.HIGH : Confidence.MEDIUM;
            Confidence confidence = Confidence.min(call.confidence(), contextConfidence);
            Severity severity = call.category() == BlockingCalls.Category.THREAD_BLOCKING
                    ? Severity.ERROR : Severity.WARNING;
            String builder = enclosing.map(region -> region.builder().methodName()).orElse("suspend");
            String scope = enclosing.map(region -> region.builder().scope()).orElse("");

            findings.add(new Finding(
                    ID, severity, confidence, invocation.location(),
                    "Blocking call " + call.description() + " " + where(enclosing, deciding, method)
                            + ". It parks a coroutine dispatcher thread and starves other coroutines"
                            + " under concurrency.",
                    "Move the call into withContext(Dispatchers.IO) { ... } so it runs on the blocking pool, or use"
                            + " the suspending equivalent (delay() instead of Thread.sleep, Deferred.await() instead"
                            + " of Future.get, a non-blocking client instead of RestTemplate/JDBC).",
                    List.of(),
                    Map.of(
                            "method", method.id().displayName(),
                            "coroutineBuilder", builder,
                            "blockingPattern", call.description(),
                            "scope", scope)));
        }
    }

    private static String where(Optional<Region> enclosing, Optional<Region> deciding, MethodModel method) {
        String place = enclosing.isPresent()
                ? "inside a '" + enclosing.get().builder().methodName() + "' coroutine lambda"
                : "in suspend function '" + method.id().displayName() + "'";
        if (deciding.isPresent() && deciding.get().dispatch() == Dispatch.CPU_OR_UI_BOUND) {
            place += " on a CPU-bound or UI dispatcher";
        }
        return place;
    }

    private static List<Region> regionsOf(MethodModel method) {
        var regions = new ArrayList<Region>();
        for (InvocationEvidence invocation : method.invocations()) {
            if (!BUILDERS.contains(invocation.methodName())) continue;
            if (invocation.arguments().stream().noneMatch(argument -> argument.strip().startsWith("{"))) continue;
            regions.add(new Region(invocation, dispatchOf(invocation)));
        }
        return regions;
    }

    private static Dispatch dispatchOf(InvocationEvidence builder) {
        for (String argument : builder.arguments()) {
            String text = argument.strip();
            if (text.startsWith("{")) continue;
            if (BLOCKING_FRIENDLY.matcher(text).find()) return Dispatch.BLOCKING_FRIENDLY;
            if (CPU_OR_UI_BOUND.matcher(text).find()) return Dispatch.CPU_OR_UI_BOUND;
            // A project or executor-backed dispatcher is a deliberate choice; give it the benefit of the doubt.
            if (ANY_DISPATCHER_ARGUMENT.matcher(text).find()) return Dispatch.BLOCKING_FRIENDLY;
        }
        return Dispatch.INHERITED;
    }

    /** The innermost enclosing builder, whatever dispatcher it names. */
    private static Optional<Region> innermostRegion(List<Region> regions, InvocationEvidence invocation) {
        Region best = null;
        // Later entries start later in the source, so on equal spans the later one is the inner one.
        for (int index = regions.size() - 1; index >= 0; index--) {
            Region region = regions.get(index);
            if (!region.encloses(invocation)) continue;
            if (best == null || region.span() < best.span()) best = region;
        }
        return Optional.ofNullable(best);
    }

    /** The innermost enclosing builder that names a dispatcher; inheriting builders defer outward. */
    private static Optional<Region> decidingRegion(List<Region> regions, InvocationEvidence invocation) {
        Region best = null;
        for (int index = regions.size() - 1; index >= 0; index--) {
            Region region = regions.get(index);
            if (!region.encloses(invocation) || region.dispatch() == Dispatch.INHERITED) continue;
            if (best == null || region.span() < best.span()) best = region;
        }
        return Optional.ofNullable(best);
    }
}
