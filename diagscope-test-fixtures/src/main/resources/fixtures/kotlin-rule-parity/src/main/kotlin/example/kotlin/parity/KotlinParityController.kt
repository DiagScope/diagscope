package example.kotlin.parity

@RestController
class KotlinParityController(private val service: KotlinParityService) {
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
        return id
    }
}
