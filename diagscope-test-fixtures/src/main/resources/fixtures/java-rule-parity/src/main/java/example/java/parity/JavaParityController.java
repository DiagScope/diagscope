package example.java.parity;

@RestController
class JavaParityController {
    private final JavaParityService service;
    private final JavaConcurrencyAndSafetyService concurrency;

    JavaParityController(JavaParityService service, JavaConcurrencyAndSafetyService concurrency) {
        this.service = service;
        this.concurrency = concurrency;
    }

    @GetMapping("/java-parity")
    String inspect(String id, String token) {
        service.logFailure(id);
        service.logFailureProperly(id);
        service.logSensitive(token);
        service.logAndRethrow(id);
        service.wrapWithCause(id);
        service.printThrowable(id);
        service.writeSystemOutput();
        service.convertFailure();
        service.submitIgnored(id);
        service.submitObserved(id);
        service.dispatchWithoutContext(id);
        service.dispatchWithContext(id);
        service.leakContext(id);
        service.fetchQuietly();
        service.fetchWithDiagnostics();
        service.fallbackQuietly();
        service.fallbackWithDiagnostics(new IllegalStateException("unavailable"));
        service.callRemote();
        service.callRemoteObserved();
        service.publishIgnored(id);
        service.publishObserved(id);
        service.transactionBoundaries(id);
        service.rollbackSuppressed(id);
        service.rollbackPropagated(id);
        service.invokeUnmanaged(id);
        service.invokeNonProxyable(id);
        service.repository.connectionLeak();
        service.repository.connectionClosedOnHappyPath();
        service.repository.connectionManagedByTry();
        service.repository.entityManagerLeak(id);
        service.repository.entityManagerClosedInFinally(id);
        service.repository.escapeJdbcTemplate();
        // Concurrency, performance and null-safety rules
        try { concurrency.processUnsafe(id); } catch (Exception ignored) {}
        concurrency.processSafe(id);
        concurrency.handleRequest(id);
        concurrency.handleRequestSafe(id);
        try { concurrency.awaitResultBlocking(); } catch (Exception ignored) {}
        try { concurrency.awaitWithTimeout(); } catch (Exception ignored) {}
        concurrency.awaitJoinBlocking();
        try { concurrency.reactiveHandlerBlockingSleep(id); } catch (Exception ignored) {}
        try { concurrency.consumeBlocking(id); } catch (Exception ignored) {}
        concurrency.enrichOrders(java.util.List.of(id));
        concurrency.enrichOrdersSafe(java.util.List.of(id));
        concurrency.loadUnchecked(id);
        concurrency.loadSafe(id);
        return id;
    }
}
