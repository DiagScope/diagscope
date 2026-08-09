package example.java.parity;

import java.util.Map;

@Service
class JavaParityService {
    private final ExecutorService executor;
    private final RemoteClient client;
    private final Logger logger;
    private final KafkaTemplate kafkaTemplate;
    final JavaRepository repository;
    private final UnmanagedWorker unmanagedWorker;

    JavaParityService(ExecutorService executor, RemoteClient client, Logger logger,
            KafkaTemplate kafkaTemplate, JavaRepository repository, UnmanagedWorker unmanagedWorker) {
        this.executor = executor;
        this.client = client;
        this.logger = logger;
        this.kafkaTemplate = kafkaTemplate;
        this.repository = repository;
        this.unmanagedWorker = unmanagedWorker;
    }

    void logFailure(String id) {
        try { client.refresh(); }
        catch (RuntimeException failure) {
            logger.error("error");
            logger.warn("Could not refresh report {}", id);
        }
    }

    void logFailureProperly(String id) {
        try { client.refresh(); }
        catch (RuntimeException failure) { logger.error("Report refresh failed for {}", id, failure); }
    }

    void logSensitive(String token) { logger.info("Calling provider with token {}", token); }

    void logAndRethrow(String id) {
        try { repository.save(id); }
        catch (RuntimeException failure) {
            logger.error("Could not save {}", id, failure);
            throw failure;
        }
    }

    void wrapWithCause(String id) {
        try { repository.save(id); }
        catch (RuntimeException failure) { throw new IllegalStateException("Could not save " + id, failure); }
    }

    void printThrowable(String id) {
        try { repository.save(id); }
        catch (RuntimeException failure) { failure.printStackTrace(); }
    }

    void writeSystemOutput() { System.err.println("Java diagnostic escaped the logger"); }

    boolean convertFailure() {
        try { client.refresh(); return true; }
        catch (RuntimeException failure) { return false; }
    }

    void submitIgnored(String id) { executor.submit(() -> repository.save(id)); }

    void submitObserved(String id) {
        Completion completion = executor.submit(() -> repository.save(id));
        completion.whenComplete((value, failure) -> {
            if (failure != null) logger.error("Async save failed", failure);
        });
    }

    void dispatchWithoutContext(String id) {
        MDC.put("orderId", id);
        executor.submit(() -> repository.save(id));
        MDC.remove("orderId");
    }

    void dispatchWithContext(String id) {
        MDC.put("orderId", id);
        Map<String, String> context = MDC.getCopyOfContextMap();
        executor.submit(() -> { MDC.setContextMap(context); repository.save(id); });
        MDC.clear();
    }

    void leakContext(String id) { MDC.put("orderId", id); repository.save(id); }

    @Retryable String fetchQuietly() { return "quiet"; }
    @Retryable @Timed("provider.fetch") String fetchWithDiagnostics() { return "instrumented"; }
    @Recover String fallbackQuietly() { return "unavailable"; }
    @Recover String fallbackWithDiagnostics(RuntimeException failure) {
        logger.warn("Returning fallback response", failure);
        return "unavailable";
    }

    RemoteResponse callRemote() { return client.remote().onErrorReturn("unavailable"); }
    RemoteResponse callRemoteObserved() {
        return client.remote().onErrorResume(failure -> client.describeFailure(failure));
    }

    void publishIgnored(String id) { kafkaTemplate.send("orders", id); }
    void publishObserved(String id) {
        kafkaTemplate.send("orders", id).whenComplete((value, failure) -> {
            if (failure != null) logger.error("Kafka publish failed", failure);
        });
    }

    void transactionBoundaries(String id) {
        auditInNewTransaction(id);
        saveRequired(id);
        requireExistingTransaction(id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void auditInNewTransaction(String id) { repository.save(id); }
    @Transactional void saveRequired(String id) { repository.save(id); }
    @Transactional(propagation = Propagation.MANDATORY)
    void requireExistingTransaction(String id) { repository.save(id); }

    @Transactional void rollbackSuppressed(String id) {
        try { repository.save(id); }
        catch (RuntimeException failure) { logger.error("Transactional save failed", failure); }
    }
    @Transactional void rollbackPropagated(String id) {
        try { repository.save(id); }
        catch (RuntimeException failure) { throw failure; }
    }

    void invokeUnmanaged(String id) { unmanagedWorker.execute(id); }
    void invokeNonProxyable(String id) { privateTransaction(id); }
    @Transactional private void privateTransaction(String id) { repository.save(id); }

    @Scheduled(cron = "0 0 * * * *")
    void scheduledQuietly() {
        try { client.refresh(); }
        catch (RuntimeException failure) { /* scheduler sees success */ }
    }
    @Scheduled(cron = "0 30 * * * *")
    void scheduledPropagated() {
        try { client.refresh(); }
        catch (RuntimeException failure) { throw failure; }
    }
}

class UnmanagedWorker {
    @Transactional String execute(String id) { return id; }
}
