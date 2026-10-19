package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports {@code EntityManager.createNativeQuery()} and related JPA/JDBC query execution methods
 * that receive a string argument containing concatenation ({@code +}).
 *
 * <p><b>Why it matters:</b> A native SQL or JPQL query built by string concatenation
 * is vulnerable to SQL injection. An attacker who controls any part of the concatenated string
 * can alter the query structure: extract data from other tables, bypass authentication checks,
 * or modify or delete rows. Native queries bypass the ORM's parameterization safety because
 * the final SQL string is sent directly to the JDBC driver.</p>
 *
 * <p>Even when the input is not directly user-controlled at the time of writing, concatenated
 * queries are fragile: a refactoring that plumbs user input into the concatenation later
 * creates a vulnerability that is not guarded. Parameterized queries ({@code :param},
 * {@code ?}) prevent this class of attack structurally — the query template is compiled
 * once and values are bound separately, so injected SQL syntax is always treated as data,
 * never as query structure.</p>
 *
 * <p><b>Detection strategy:</b> Find invocations of known query-creation methods whose first
 * argument text contains the string {@code " + "} (Java/Kotlin string concatenation operator).
 * Confidence is MEDIUM because the argument text representation may not always be complete
 * in the AST snapshot.</p>
 */
public final class NativeQueryStringConcatRule implements ProjectRule {

    public static final String ID = "NATIVE_QUERY_STRING_CONCAT";

    private static final Set<String> QUERY_METHODS = Set.of(
            "createNativeQuery", "createQuery", "createNamedQuery",
            "nativeQuery", "query", "execute", "batchUpdate", "queryForObject",
            "queryForList", "queryForMap"
    );

    /** Names that also mean something else on an executor, a cache or a client. */
    private static final Set<String> GENERIC_NAMES = Set.of("query", "execute", "batchUpdate");

    private static final java.util.regex.Pattern DATABASE_RECEIVER = java.util.regex.Pattern.compile(
            "(?i)jdbc|template|statement|entitymanager|session|query|dao|repositor|connection|datasource|\\bem\\b|hibernate|database|\\bdb\\b|sql");

    private static final java.util.regex.Pattern CONSTANT_NAME = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]*(\\.[A-Z][A-Z0-9_]*)*");

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
        for (InvocationEvidence inv : method.invocations()) {
            if (!QUERY_METHODS.contains(inv.methodName())) continue;
            if (inv.arguments().isEmpty()) continue;

            String firstArg = inv.arguments().get(0);
            if (firstArg == null || !firstArg.contains(" + ")) continue;
            // execute(() -> a + b) on an Executor is not a query, and neither is a name this generic on a non-database receiver.
            if (firstArg.contains("->")) continue;
            if (GENERIC_NAMES.contains(inv.methodName()) && !DATABASE_RECEIVER.matcher(RuleSupport.hintText(inv)).find()) continue;
            // Literals and constants joined together are one fixed string; only a variable can carry an injection.
            if (!hasDynamicOperand(firstArg)) continue;

            String display = method.id().displayName();
            findings.add(new Finding(
                    ID, Severity.ERROR, Confidence.MEDIUM, inv.location(),
                    "'" + display + "' builds a query string with concatenation (+)."
                            + " Concatenated queries are vulnerable to SQL injection:"
                            + " an attacker who controls any concatenated fragment can alter"
                            + " the query structure, extract data, or modify rows.",
                    "Use parameterized queries. For JPA: entityManager.createNativeQuery("
                            + "\"SELECT * FROM orders WHERE id = :id\").setParameter(\"id\", id)."
                            + " For JdbcTemplate: jdbcTemplate.queryForObject(\"SELECT...\","
                            + " new Object[]{id}, RowMapper). Never concatenate user-supplied"
                            + " or externally derived values into a SQL/JPQL string.",
                    List.of(),
                    Map.of(
                            "method", display,
                            "queryMethod", inv.methodName(),
                            "declaringType", method.id().declaringType()
                    )));
            return; // one finding per method
        }
    }

    /**
     * True when the expression joins at least one operand that is neither a string or number literal nor an
     * UPPER_CASE constant. Splits on the top-level {@code +}, ignoring those inside quotes and brackets.
     */
    static boolean hasDynamicOperand(String expression) {
        var operands = new java.util.ArrayList<String>();
        var current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\\' && i + 1 < expression.length()) current.append(expression.charAt(++i));
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; current.append(c); continue; }
            if (c == '(' || c == '[' || c == '{') depth++;
            if (c == ')' || c == ']' || c == '}') depth--;
            if (c == '+' && depth == 0) { operands.add(current.toString().strip()); current.setLength(0); continue; }
            current.append(c);
        }
        operands.add(current.toString().strip());
        return operands.stream().anyMatch(operand -> !operand.isEmpty() && !isFixed(operand));
    }

    private static boolean isFixed(String operand) {
        return (operand.startsWith("\"") && operand.endsWith("\"") && operand.length() >= 2)
                || operand.matches("-?\\d+(\\.\\d+)?[LlFfDd]?")
                || CONSTANT_NAME.matcher(operand).matches();
    }
}
