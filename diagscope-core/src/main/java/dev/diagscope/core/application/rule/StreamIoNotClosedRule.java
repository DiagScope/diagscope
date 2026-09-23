package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports {@code Files.list()}, {@code Files.walk()}, {@code Files.lines()}, and similar
 * I/O-backed stream factories called outside a try-with-resources block.
 *
 * <p>These methods return a {@code java.util.stream.Stream} that wraps an underlying OS file
 * handle. Unlike collection streams, I/O streams must be closed after use. Without closing,
 * the file handle remains open until the stream is garbage-collected — which may never happen
 * in a long-running service. The JVM file-handle limit (typically 1024–65536 per process)
 * is exhausted silently over time, eventually causing {@code IOException: Too many open files}
 * on unrelated operations.</p>
 *
 * <p><b>Why it matters:</b> In a REST service that processes files (uploads, exports,
 * reports), each unclosed {@code Files.walk()} or {@code Files.lines()} call leaks one file
 * descriptor. A burst of 100 requests can exhaust the handle budget and take the entire JVM
 * down — affecting every endpoint, not just file-related ones. The failure typically surfaces
 * at 2-3 AM during a batch run, with a misleading error on a database connection pool that
 * also uses file descriptors.</p>
 *
 * <p><b>Detection strategy:</b></p>
 * <ol>
 *   <li>Identify invocations of {@code Files.list}, {@code Files.walk}, {@code Files.lines},
 *       or {@code BufferedReader.lines} where the receiver type or scope suggests the
 *       {@code Files} utility class or a buffered reader.</li>
 *   <li>Check whether the invocation is inside a resource-managed (try-with-resources) block
 *       via the {@code resourceManaged} flag on {@code InvocationEvidence}.</li>
 *   <li>Emit WARNING with HIGH confidence when the receiver type is exactly {@code Files}
 *       or MEDIUM when inferred from method name alone.</li>
 * </ol>
 *
 * <p><b>Known limitations:</b> The rule does not track whether the result is assigned to a
 * variable later closed manually in a {@code finally} block — that pattern suppresses the
 * real leak but would still be flagged. Suppress with {@code diagscope:ignore} when manual
 * close is verified.</p>
 */
public final class StreamIoNotClosedRule implements DiagnosticRule {

    public static final String ID = "STREAM_IO_NOT_CLOSED";

    private static final Set<String> IO_STREAM_METHODS = Set.of("list", "walk", "lines", "find");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(Flow flow) {
        var findings = new ArrayList<Finding>();
        for (var flowMethod : flow.methods()) {
            var method = flowMethod.method();
            for (var invocation : method.invocations()) {
                if (!isIoStreamFactory(invocation)) continue;
                if (invocation.resourceManaged()) continue;

                boolean highConfidence = isFilesClass(invocation);
                var confidence = Confidence.min(
                        highConfidence ? Confidence.HIGH : Confidence.MEDIUM,
                        flowMethod.confidence());

                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "I/O stream factory '" + invocation.methodName()
                                + "()' returns a stream backed by an OS file handle"
                                + " and is not wrapped in a try-with-resources block."
                                + " The file handle will not be closed until the stream"
                                + " is garbage-collected, leaking OS resources.",
                        "Wrap the call in try-with-resources so the stream is always closed:"
                                + " try (var stream = Files." + invocation.methodName() + "(path)) { ... }."
                                + " Alternatively use Files.readAllLines() for small files"
                                + " (it reads and closes atomically), or Files.readString()"
                                + " for text content that fits comfortably in memory.",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of(
                                "method", method.id().displayName(),
                                "streamFactory", invocation.methodName(),
                                "resourceManaged", "false"
                        )));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isIoStreamFactory(InvocationEvidence invocation) {
        if (!IO_STREAM_METHODS.contains(invocation.methodName())) return false;
        return isFilesClass(invocation) || isBufferedReader(invocation);
    }

    private static boolean isFilesClass(InvocationEvidence invocation) {
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase();
        return hint.contains("files");
    }

    private static boolean isBufferedReader(InvocationEvidence invocation) {
        String hint = (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase();
        return hint.contains("bufferedreader") || hint.contains("reader");
    }
}
