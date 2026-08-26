package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reports Spring Data repository methods that return an unbounded collection ({@code List},
 * {@code Collection}, {@code Iterable}, {@code Set}) without accepting a {@code Pageable}
 * parameter.
 *
 * <p>A repository method that returns all matching rows with no {@code LIMIT} works fine in
 * development and testing with a small dataset. As data grows, the same query loads thousands
 * or millions of rows into memory, triggering OOM errors or request timeouts that only appear
 * months after go-live. The pattern is not covered by typical load tests because they rarely
 * use production-sized datasets.</p>
 *
 * <p>Detection heuristic:</p>
 * <ol>
 *   <li>The declaring type name (simple name) ends with {@code Repository} or {@code Dao},
 *       or the type annotations include {@code Repository} — indicating it is a Spring Data or
 *       plain DAO type.</li>
 *   <li>The return type is a collection type: starts with {@code List}, {@code Collection},
 *       {@code Iterable}, {@code Set}, {@code Flux}, or {@code Flow} (reactive variants).</li>
 *   <li>No parameter type is {@code Pageable} or {@code PageRequest}.</li>
 *   <li>The method name does not contain {@code count}, {@code exist}, {@code delete}, or
 *       {@code save} — to exclude aggregate/write operations that intentionally return lists.</li>
 * </ol>
 *
 * <p>Confidence is MEDIUM because the heuristic matches by type name pattern; a type named
 * {@code OrderRepository} that is not a Spring Data interface will produce a false positive if
 * it has a {@code findAll()} returning a list.</p>
 */
public final class MissingPaginationRule implements ProjectRule {

    public static final String ID = "MISSING_PAGINATION";

    // Return type prefixes that indicate an unbounded collection
    private static final Set<String> COLLECTION_PREFIXES = Set.of(
            "List<", "list<",
            "Collection<", "collection<",
            "Iterable<", "iterable<",
            "Set<", "set<",
            "ArrayList<", "LinkedList<",
            "Flux<",        // reactive
            "Flow<"         // Kotlin coroutines
    );

    // Parameter types that indicate pagination is already handled
    private static final Set<String> PAGEABLE_TYPES = Set.of(
            "Pageable", "PageRequest", "Sort"
    );

    // Method name tokens that indicate non-query operations — skip these
    private static final Pattern NON_QUERY_NAME = Pattern.compile(
            "(?i).*(count|exist|delete|save|update|insert|flush|merge|persist).*");

    // Declaring type name suffix pattern
    private static final Pattern REPO_TYPE = Pattern.compile(
            "(?i).*(repository|dao|dataaccess|store).*");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (var method : project.methods().values()) {
            check(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void check(MethodModel method, List<Finding> findings) {
        if (!isRepositoryType(method)) return;
        if (!returnsCollection(method.returnType())) return;
        if (hasPageableParam(method)) return;
        if (NON_QUERY_NAME.matcher(method.id().name()).matches()) return;

        findings.add(new Finding(
                ID, Severity.WARNING, Confidence.MEDIUM, method.location(),
                "Repository method '" + method.id().displayName()
                        + "' returns an unbounded collection ('" + simplify(method.returnType())
                        + "') without a Pageable parameter. This query has no LIMIT and will"
                        + " load the entire result set into memory as data grows.",
                "Add a 'Pageable' parameter and change the return type to 'Page<T>' for paginated"
                        + " access: 'Page<" + elementType(method.returnType()) + "> "
                        + method.id().name() + "(..., Pageable pageable)'. If the full result"
                        + " is intentional (e.g., batch export), document this explicitly and"
                        + " ensure it is never called from a user-facing request path.",
                List.of(),
                Map.of(
                        "method", method.id().displayName(),
                        "returnType", method.returnType(),
                        "declaringType", method.id().declaringType()
                )));
    }

    private static boolean isRepositoryType(MethodModel method) {
        String simpleType = simpleTypeName(method.id().declaringType());
        if (REPO_TYPE.matcher(simpleType).matches()) return true;
        // Also match if the class is annotated with @Repository
        return method.annotations().stream().anyMatch(a -> a.equalsIgnoreCase("Repository"));
    }

    private static boolean returnsCollection(String returnType) {
        if (returnType == null || returnType.isBlank()) return false;
        String stripped = returnType.strip();
        return COLLECTION_PREFIXES.stream().anyMatch(stripped::startsWith);
    }

    private static boolean hasPageableParam(MethodModel method) {
        return method.id().parameterTypes().stream()
                .map(MissingPaginationRule::simpleTypeName)
                .anyMatch(PAGEABLE_TYPES::contains);
    }

    private static String simpleTypeName(String qualifiedOrSimple) {
        if (qualifiedOrSimple == null) return "";
        int dot = qualifiedOrSimple.lastIndexOf('.');
        return dot >= 0 ? qualifiedOrSimple.substring(dot + 1) : qualifiedOrSimple;
    }

    private static String simplify(String returnType) {
        if (returnType == null) return "";
        int lt = returnType.indexOf('<');
        return lt > 0 ? returnType.substring(0, lt) + "<…>" : returnType;
    }

    /** Extracts the element type from a generic collection return type, or returns 'T'. */
    private static String elementType(String returnType) {
        if (returnType == null) return "T";
        int lt = returnType.indexOf('<');
        int gt = returnType.lastIndexOf('>');
        if (lt > 0 && gt > lt) return returnType.substring(lt + 1, gt);
        return "T";
    }
}
