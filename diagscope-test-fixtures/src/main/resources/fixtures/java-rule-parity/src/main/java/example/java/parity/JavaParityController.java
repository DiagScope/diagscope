package example.java.parity;

@RestController
class JavaParityController {
    private final JavaParityService service;
    private final JavaConcurrencyAndSafetyService concurrency;
    private final JavaMaintainabilityService maintainability;
    private final JavaTransactionAndHttpService transactionHttp;
    private final JavaBatchProcessorService batchProcessor;

    JavaParityController(JavaParityService service, JavaConcurrencyAndSafetyService concurrency,
                         JavaMaintainabilityService maintainability,
                         JavaTransactionAndHttpService transactionHttp,
                         JavaBatchProcessorService batchProcessor) {
        this.service = service;
        this.concurrency = concurrency;
        this.maintainability = maintainability;
        this.transactionHttp = transactionHttp;
        this.batchProcessor = batchProcessor;
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
        // Maintainability and atomic-operations rules
        maintainability.registerIfAbsent(id, id);
        maintainability.registerIfAbsentSafe(id, id);
        maintainability.addIfNotPresent(id);
        maintainability.createOrder(id, id, id, id, id, id);
        maintainability.createOrderSafe(new JavaMaintainabilityService.OrderRequest(id, id, id, id, id, id));
        // Transaction and HTTP timeout rules
        transactionHttp.saveWithoutTransaction(id);
        transactionHttp.saveWithTransaction(id);
        transactionHttp.saveInsideReadOnlyTransaction(id);
        transactionHttp.findAndDereference(id);
        transactionHttp.callWithoutTimeout(id);
        transactionHttp.callWithTimeout(id);
        // Wave 6: null-safety and transaction rules
        transactionHttp.lookupOrNull(id);
        transactionHttp.lookupWithFallback(id);
        transactionHttp.getAndDereference(id);
        transactionHttp.getWithDefault(id);
        transactionHttp.fetchWithDirtyReads();
        transactionHttp.fetchWithDefaultIsolation();
        transactionHttp.importEntities(java.util.List.of(id));
        transactionHttp.importEntitiesSafe(java.util.List.of(id));
        transactionHttp.processItemBatch(java.util.List.of(id));
        batchProcessor.processAllItems(java.util.List.of(id));
        batchProcessor.processAllItemsSafe(java.util.List.of(id));
        return id;
    }
}
