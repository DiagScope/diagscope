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
 * Reports methods that perform a database write and a messaging send in the same scope without a
 * transactional outbox mechanism.
 *
 * <p>If the application crashes between the database commit and the Kafka (or any broker) send,
 * the event is silently lost: the database reflects the new state, but downstream consumers never
 * receive the event. This is a silent split-brain that only surfaces under failure conditions and
 * is extremely difficult to reconcile retroactively — especially in financial, audit, or
 * event-sourced systems.</p>
 *
 * <p>The idiomatic fix is the <em>Transactional Outbox</em> pattern: persist the outbound event to
 * an outbox table inside the same database transaction, then relay it asynchronously (Debezium CDC,
 * a polling relay, etc.). A lighter-weight alternative is to use
 * {@code ApplicationEventPublisher.publishEvent()} inside the transaction and handle the publish in
 * a {@code @TransactionalEventListener(phase = AFTER_COMMIT)} method.</p>
 *
 * <p>Suppression: if the method is annotated with {@code @TransactionalEventListener} or delegates
 * the send via {@code publishEvent()}, the finding is skipped — those patterns decouple the commit
 * from the send correctly.</p>
 */
public final class OutboxPatternMissingRule implements ProjectRule {

    public static final String ID = "OUTBOX_PATTERN_MISSING";

    // Write operations on database/persistence types
    private static final Set<String> WRITE_METHODS = Set.of(
            "save", "saveandflush", "saveall", "saveallflush",
            "delete", "deletebyid", "deleteall", "deleteallbyid",
            "insert", "update", "upsert", "persist", "merge",
            "executeupdate", "executebatch", "flush"
    );

    // Receiver-type fragments that indicate a database or persistence layer
    private static final Pattern REPO_TYPE = Pattern.compile(
            "(?i)(repository|dao|jparepository|crudrepository|reactivecrudrepository|"
                    + "entitymanager|jdbctemplate|namedparameterjdbctemplate|mongotemplate|"
                    + "mongocollection|mongorepository|r2dbcrepository|cassandrarepository|"
                    + "redisrepository|cosmosrepository|dynamodbmapper)");

    // Messaging send operations
    private static final Set<String> SEND_METHODS = Set.of(
            "send", "sendmessage", "senddefault", "sendoffsetstotransaction",
            "publish", "convertandsend", "convertandsendtouser",
            "emit", "dispatch", "sendtobinding"
    );

    // Receiver-type fragments that indicate a messaging or event-broker layer
    private static final Pattern MESSAGING_TYPE = Pattern.compile(
            "(?i)(kafkatemplate|kafkaproducer|reactorkafkaproducer|"
                    + "rabbittemplate|amqptemplate|jmstemplate|jmsmessageproducer|"
                    + "messagechannel|messaginggateway|snstemplate|sqstemplate|"
                    + "eventbridgeproducer|pubsubtemplate|servicebussender)");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<Finding> evaluate(AnalyzedProject project) {
        var findings = new ArrayList<Finding>();
        for (var method : project.methods().values()) {
            evaluate(method, findings);
        }
        return List.copyOf(findings);
    }

    private static void evaluate(MethodModel method, List<Finding> findings) {
        // Safe pattern: @TransactionalEventListener methods ARE the after-commit publisher
        if (DiagnosticSignals.hasAnnotation(method, "TransactionalEventListener")) return;

        InvocationEvidence write = firstRepoWrite(method);
        if (write == null) return;

        InvocationEvidence send = firstMessagingSend(method);
        if (send == null) return;

        // Safe pattern: method delegates to ApplicationEventPublisher.publishEvent()
        if (hasPublishEvent(method)) return;

        boolean repoAmbiguous = !REPO_TYPE.matcher(write.receiverType()).find()
                && !REPO_TYPE.matcher(write.scope()).find();
        boolean messagingAmbiguous = !MESSAGING_TYPE.matcher(send.receiverType()).find()
                && !MESSAGING_TYPE.matcher(send.scope()).find();

        Confidence confidence;
        if (!repoAmbiguous && !messagingAmbiguous) {
            confidence = Confidence.HIGH;
        } else if (repoAmbiguous && messagingAmbiguous) {
            // Both sides are ambiguous — too risky to report at LOW, skip
            return;
        } else {
            confidence = Confidence.MEDIUM;
        }

        findings.add(new Finding(
                ID, Severity.WARNING, confidence, write.location(),
                "Database write and messaging send in the same method without a transactional outbox."
                        + " A crash between the commit and the send causes the event to be silently lost,"
                        + " leaving the database and consumers in inconsistent state.",
                "Use the Transactional Outbox pattern: write the event to an outbox table in the same"
                        + " transaction and relay it asynchronously via CDC (Debezium) or a polling relay."
                        + " A lighter alternative: call ApplicationEventPublisher.publishEvent() inside"
                        + " the transaction and publish in a @TransactionalEventListener(AFTER_COMMIT).",
                List.of(),
                Map.of(
                        "method", method.id().displayName(),
                        "repoWrite", write.scope() + "." + write.methodName() + "()",
                        "messagingSend", send.scope() + "." + send.methodName() + "()"
                )));
    }

    private static InvocationEvidence firstRepoWrite(MethodModel method) {
        for (var inv : method.invocations()) {
            String name = inv.methodName().toLowerCase(Locale.ROOT);
            if (!WRITE_METHODS.contains(name)) continue;
            if (REPO_TYPE.matcher(inv.receiverType()).find()
                    || REPO_TYPE.matcher(inv.scope()).find()) {
                return inv;
            }
        }
        return null;
    }

    private static InvocationEvidence firstMessagingSend(MethodModel method) {
        for (var inv : method.invocations()) {
            String name = inv.methodName().toLowerCase(Locale.ROOT);
            if (!SEND_METHODS.contains(name)) continue;
            if (MESSAGING_TYPE.matcher(inv.receiverType()).find()
                    || MESSAGING_TYPE.matcher(inv.scope()).find()) {
                return inv;
            }
        }
        return null;
    }

    private static boolean hasPublishEvent(MethodModel method) {
        return method.invocations().stream()
                .anyMatch(inv -> "publishevent".equals(inv.methodName().toLowerCase(Locale.ROOT)));
    }
}
