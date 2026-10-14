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
 * Reports a REST handler that builds a file path from a non-constant argument.
 *
 * <p>{@code new File(name)}, {@code Paths.get(base, name)} and {@code Path.of(name)} with request-derived input
 * let {@code ../../etc/passwd} escape the intended directory and read or overwrite arbitrary files.</p>
 *
 * <p>Any non-constant argument counts. Confidence is HIGH when the argument names a method parameter or the method
 * reads the request object, and MEDIUM otherwise (for example a path taken from configuration). It stays silent when the method visibly neutralises the input ({@code normalize}, {@code toRealPath},
 * {@code getCanonicalPath}, {@code startsWith}, {@code getFileName}, {@code FilenameUtils.getName}); a check in
 * another method is not seen.</p>
 */
public final class PathTraversalRiskRule implements ProjectRule {

    public static final String ID = "PATH_TRAVERSAL_RISK";

    private static final Set<String> SANITIZING_CALLS = Set.of(
            "normalize", "toRealPath", "getCanonicalPath", "getCanonicalFile", "startsWith", "getFileName",
            "sanitize", "sanitizeFilename", "isSubPath");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (MethodModel method : project.methods().values()) {
            if (!RuleSupport.isRestEndpoint(method)) continue;
            if (RuleSupport.calls(method, SANITIZING_CALLS.toArray(String[]::new)) || usesFilenameUtils(method)) continue;
            for (InvocationEvidence invocation : method.invocations()) {
                String call = pathConstruction(invocation);
                if (call == null || invocation.arguments().stream().noneMatch(RuleSupport::isDynamicExpression)) continue;
                boolean requestData = invocation.arguments().stream().anyMatch(arg -> RuleSupport.carriesRequestData(method, arg));
                findings.add(new Finding(
                        ID, Severity.ERROR, requestData ? Confidence.HIGH : Confidence.MEDIUM, invocation.location(),
                        "'" + method.id().displayName() + "' builds a file path with " + call + " from a non-constant"
                                + " argument. If it comes from the request, '../' sequences can escape the intended directory.",
                        "Resolve the user-supplied name against a fixed base directory, call normalize() (or toRealPath())"
                                + " and verify the result still startsWith(base). Better: never accept a path, accept an id and"
                                + " look the file up yourself.",
                        List.of(),
                        Map.of("method", method.id().displayName(), "call", call)));
            }
        }
        return List.copyOf(findings);
    }

    private static String pathConstruction(InvocationEvidence invocation) {
        if (RuleSupport.isConstructorOf(invocation, "File") && !invocation.arguments().isEmpty()) return "new File(...)";
        if (invocation.methodName().equals("get") && RuleSupport.isCallOn(invocation, "Paths")) return "Paths.get(...)";
        if (invocation.methodName().equals("of") && RuleSupport.isCallOn(invocation, "Path")) return "Path.of(...)";
        return null;
    }

    private static boolean usesFilenameUtils(MethodModel method) {
        return method.invocations().stream().anyMatch(call ->
                call.methodName().equals("getName") && RuleSupport.isCallOn(call, "FilenameUtils"));
    }
}
