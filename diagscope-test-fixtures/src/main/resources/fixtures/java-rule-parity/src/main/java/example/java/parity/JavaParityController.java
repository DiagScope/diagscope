package example.java.parity;

@RestController
class JavaParityController {
    private final JavaParityService service;

    JavaParityController(JavaParityService service) { this.service = service; }

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
        return id;
    }
}
