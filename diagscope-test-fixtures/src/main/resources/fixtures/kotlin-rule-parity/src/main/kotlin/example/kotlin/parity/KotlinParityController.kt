package example.kotlin.parity

@RestController
class KotlinParityController(
    private val service: KotlinParityService,
    private val concurrency: KotlinConcurrencyAndSafetyService,
    private val maintainability: KotlinMaintainabilityService,
    private val transactionHttp: KotlinTransactionAndHttpService
) {
    @GetMapping("/kotlin-parity")
    fun inspect(id: String, token: String): String {
        service.logFailure(id)
        service.logFailureProperly(id)
        service.logSensitive(token)
        service.logAndRethrow(id)
        service.wrapWithCause(id)
        service.printThrowable(id)
        service.writeSystemOutput()
        service.convertFailure()
        service.submitIgnored(id)
        service.submitObserved(id)
        service.dispatchWithoutContext(id)
        service.dispatchWithContext(id)
        service.leakContext(id)
        service.fetchQuietly()
        service.fetchWithDiagnostics()
        service.fallbackQuietly()
        service.fallbackWithDiagnostics(IllegalStateException("unavailable"))
        service.callRemote()
        service.callRemoteObserved()
        service.publishIgnored(id)
        service.publishObserved(id)
        service.transactionBoundaries(id)
        service.rollbackSuppressed(id)
        service.rollbackPropagated(id)
        service.invokeUnmanaged(id)
        service.repository.connectionLeak()
        service.repository.connectionClosedOnHappyPath()
        service.repository.connectionManagedByUse()
        service.repository.entityManagerLeak(id)
        service.repository.entityManagerClosedInFinally(id)
        service.repository.escapeJdbcTemplate()
        // Concurrency, performance and null-safety rules
        runCatching { concurrency.processUnsafe(id) }
        concurrency.processSafe(id)
        concurrency.handleRequest(id)
        concurrency.handleRequestSafe(id)
        runCatching { concurrency.awaitResultBlocking() }
        runCatching { concurrency.awaitWithTimeout() }
        concurrency.awaitJoinBlocking()
        runCatching { concurrency.reactiveHandlerBlockingSleep(id) }
        runCatching { concurrency.consumeBlocking(id) }
        concurrency.enrichOrders(listOf(id))
        concurrency.enrichOrdersSafe(listOf(id))
        concurrency.loadUnchecked(id)
        concurrency.loadSafe(id)
        // Maintainability and atomic-operations rules
        maintainability.registerIfAbsent(id, id)
        maintainability.registerIfAbsentSafe(id, id)
        maintainability.addIfNotPresent(id)
        maintainability.createOrder(id, id, id, id, id, id)
        maintainability.createOrderSafe(KotlinMaintainabilityService.OrderRequest(id, id, id, id, id, id))
        // Transaction and HTTP timeout rules
        transactionHttp.saveWithoutTransaction(id)
        transactionHttp.saveWithTransaction(id)
        transactionHttp.callWithoutTimeout(id)
        transactionHttp.callWithTimeout(id)
        return id
    }
}
