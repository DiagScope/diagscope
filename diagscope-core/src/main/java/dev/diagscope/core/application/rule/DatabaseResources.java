package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.MethodModel;

import java.util.Locale;
import java.util.Set;

/**
 * Shared recognition of database resource acquisition and release, used by the JDBC, JPA and
 * {@code JdbcTemplate} rules so they classify the same call the same way.
 */
final class DatabaseResources {

    /** Calls that hand back a resource the caller owns and must close. */
    static final Set<String> JDBC_ACQUISITIONS = Set.of(
            "getConnection", "createStatement", "prepareStatement", "prepareCall",
            "executeQuery", "getResultSet", "getGeneratedKeys");

    private static final Set<String> JDBC_RECEIVER_HINTS = Set.of(
            "datasource", "connection", "statement", "preparedstatement", "callablestatement",
            "resultset");

    private static final Set<String> RELEASES = Set.of("close", "releaseConnection", "closeConnection");

    private DatabaseResources() {
    }

    /** True when the receiver of the call is recognisably a JDBC handle rather than an unrelated API. */
    static boolean looksJdbc(InvocationEvidence invocation) {
        if ("getConnection".equals(invocation.methodName())) {
            return true;
        }
        String receiver = hint(invocation);
        return JDBC_RECEIVER_HINTS.stream().anyMatch(receiver::contains);
    }

    /** True when the call obtains a container- or application-managed JPA {@code EntityManager}. */
    static boolean createsEntityManager(InvocationEvidence invocation) {
        return "createEntityManager".equals(invocation.methodName());
    }

    /** True when the call reaches the raw connection behind a {@code JdbcTemplate} or {@code DataSourceUtils}. */
    static boolean escapesJdbcTemplate(InvocationEvidence invocation) {
        if (!"getConnection".equals(invocation.methodName())) {
            return false;
        }
        String hint = hint(invocation);
        return hint.contains("jdbctemplate") || hint.contains("jdbcoperations")
                || hint.contains("datasourceutils") || hint.contains("namedparameterjdbc");
    }

    /**
     * Reports how the method releases {@code variable}: not at all, only on the success path, or on
     * every path because the release runs from a {@code finally} block.
     */
    static Release releaseOf(MethodModel method, String variable) {
        if (variable.isBlank()) {
            return Release.NONE;
        }
        Release release = Release.NONE;
        for (InvocationEvidence invocation : method.invocations()) {
            if (!isRelease(invocation, variable)) {
                continue;
            }
            if (invocation.insideFinally()) {
                return Release.GUARDED;
            }
            release = Release.HAPPY_PATH_ONLY;
        }
        return release;
    }

    private static boolean isRelease(InvocationEvidence invocation, String variable) {
        if (!RELEASES.contains(invocation.methodName())) {
            return false;
        }
        if (variable.equals(invocation.scope())) {
            return true;
        }
        // DataSourceUtils.releaseConnection(connection, dataSource) and friends pass the handle along.
        return invocation.arguments().stream().anyMatch(variable::equals);
    }

    private static String hint(InvocationEvidence invocation) {
        return (invocation.scope() + ' ' + invocation.receiverType()).toLowerCase(Locale.ROOT);
    }

    /** How a resource variable is released inside the method that acquired it. */
    enum Release {
        /** No release call is visible in the method. */
        NONE,
        /** Released, but only where the success path reaches it — a throw skips the release. */
        HAPPY_PATH_ONLY,
        /** Released from a {@code finally} block, so the failure path releases it too. */
        GUARDED
    }
}
