package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.RelatedFlow;
import dev.diagscope.core.domain.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reports database or repository calls made inside a loop body, which is the classic N+1 query
 * anti-pattern: one outer query returns N rows, then the loop fires one query per row.
 *
 * <p>N+1 queries grow linearly with the collection size. At scale they overwhelm the database,
 * inflate response latency, and are almost never visible in unit tests that work on tiny fixtures.
 * The fix is to use a batch/join query or Spring Data projections that fetch all required data in
 * a single round trip.</p>
 */
public final class NPlusOneQueryRiskRule implements DiagnosticRule {
    public static final String ID = "N_PLUS_ONE_QUERY_RISK";

    /** Spring Data, Micronaut Data and similar repository methods. */
    private static final Pattern FINDER_METHOD = Pattern.compile(
            "(?i)^(find|get|load|fetch|query|read|search|list|count|exists|delete|remove|save"
                    + "|update|merge|persist|insert|execute|refresh|detach|flush).*");

    /** JPA / Hibernate EntityManager operations that hit the database. */
    private static final Set<String> JPA_METHODS = Set.of(
            "find", "getReference", "getReferenceById", "refresh", "merge", "persist",
            "remove", "flush", "createQuery", "createNativeQuery", "createNamedQuery",
            "createStoredProcedureQuery");

    /** Types whose name strongly suggests a persistence boundary. */
    private static final Pattern REPO_TYPE = Pattern.compile(
            "(?i).*(repository|dao|jparepository|crudrepository|pagingandsortingrepository"
                    + "|reactivecrudrepository|mongorepository|elasticsearchrepository"
                    + "|r2dbcrepository|entitymanager|jdbctemplate|namedparameterjdbctemplate"
                    + "|mongotemplate|reactiveMongotemplate|r2dbcclient).*");

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
                if (!invocation.insideLoop()) continue;
                if (!isDatabaseCall(invocation)) continue;

                // Confidence: HIGH when receiver type is unambiguous, MEDIUM otherwise
                boolean unambiguous = !invocation.receiverType().isBlank()
                        && REPO_TYPE.matcher(invocation.receiverType()).matches();
                Confidence confidence = Confidence.min(
                        unambiguous ? Confidence.HIGH : Confidence.MEDIUM,
                        flowMethod.confidence());

                findings.add(new Finding(
                        ID, Severity.WARNING, confidence, invocation.location(),
                        "Database call inside a loop: potential N+1 query pattern.",
                        "Fetch all required data before the loop with a batch query, a JOIN FETCH,"
                                + " or a Spring Data findAll(ids) / findAllById(ids).",
                        List.of(RelatedFlow.from(flow.entrypoint(), flowMethod, confidence)),
                        Map.of("method", method.id().displayName(),
                                "call", invocation.scope() + '.' + invocation.methodName(),
                                "receiverType", invocation.receiverType())
                ));
            }
        }
        return List.copyOf(findings);
    }

    private static boolean isDatabaseCall(InvocationEvidence invocation) {
        String receiverHint = (invocation.scope() + ' ' + invocation.receiverType())
                .toLowerCase(Locale.ROOT);
        boolean repoReceiver = REPO_TYPE.matcher(receiverHint).matches();

        // JPA EntityManager method names are well-known and short
        if (JPA_METHODS.contains(invocation.methodName()) && receiverHint.contains("entitymanager")) {
            return true;
        }

        // Spring Data / DAO pattern: findBy*, getBy*, etc. on a repository receiver
        if (repoReceiver && FINDER_METHOD.matcher(invocation.methodName()).matches()) {
            return true;
        }

        // Template-style: any method on JdbcTemplate / MongoTemplate whose name implies a query
        if ((receiverHint.contains("template") || receiverHint.contains("jdbctemplate"))
                && FINDER_METHOD.matcher(invocation.methodName()).matches()) {
            return true;
        }

        return false;
    }
}
