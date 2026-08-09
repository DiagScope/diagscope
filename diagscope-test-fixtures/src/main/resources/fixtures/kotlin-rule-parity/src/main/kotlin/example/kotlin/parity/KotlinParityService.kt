package example.kotlin.parity

@Service
class KotlinParityService(
    private val executor: ExecutorService,
    private val client: RemoteClient,
    private val logger: Logger,
    private val kafkaTemplate: KafkaTemplate,
    val repository: KotlinRepository,
    private val unmanagedWorker: UnmanagedWorker
) {
    fun logFailure(id: String) {
        try {
            client.refresh()
        } catch (failure: RuntimeException) {
            logger.error("error")
            logger.warn("Could not refresh report {}", id)
        }
    }

    fun logFailureProperly(id: String) {
        try {
            client.refresh()
        } catch (failure: RuntimeException) {
            logger.error("Report refresh failed for {}", id, failure)
        }
    }

    fun logSensitive(token: String) {
        logger.info("Calling provider with token {}", token)
    }

    fun logAndRethrow(id: String) {
        try {
            repository.save(id)
        } catch (failure: RuntimeException) {
            logger.error("Could not save {}", id, failure)
            throw failure
        }
    }

    fun wrapWithCause(id: String) {
        try {
            repository.save(id)
        } catch (failure: RuntimeException) {
            throw IllegalStateException("Could not save $id", failure)
        }
    }

    fun printThrowable(id: String) {
        try {
            repository.save(id)
        } catch (failure: RuntimeException) {
            failure.printStackTrace()
        }
    }

    fun writeSystemOutput() {
        System.err.println("Kotlin diagnostic escaped the logger")
    }

    fun convertFailure(): Boolean = try {
        client.refresh()
        true
    } catch (failure: RuntimeException) {
        false
    }

    fun submitIgnored(id: String) {
        executor.submit { repository.save(id) }
    }

    fun submitObserved(id: String) {
        val completion = executor.submit { repository.save(id) }
        completion.whenComplete { _, failure ->
            if (failure != null) logger.error("Async save failed", failure)
        }
    }

    fun dispatchWithoutContext(id: String) {
        MDC.put("orderId", id)
        executor.submit { repository.save(id) }
        MDC.remove("orderId")
    }

    fun dispatchWithContext(id: String) {
        MDC.put("orderId", id)
        val context = MDC.getCopyOfContextMap()
        executor.submit {
            MDC.setContextMap(context)
            repository.save(id)
        }
        MDC.clear()
    }

    fun leakContext(id: String) {
        MDC.put("orderId", id)
        repository.save(id)
    }

    @Retryable
    fun fetchQuietly(): String = "quiet"

    @Retryable
    @Timed("provider.fetch")
    fun fetchWithDiagnostics(): String = "instrumented"

    @Recover
    fun fallbackQuietly(): String = "unavailable"

    @Recover
    fun fallbackWithDiagnostics(failure: RuntimeException): String {
        logger.warn("Returning fallback response", failure)
        return "unavailable"
    }

    fun callRemote(): RemoteResponse = client.remote().onErrorReturn("unavailable")

    fun callRemoteObserved(): RemoteResponse = client.remote().onErrorResume { failure ->
        client.describeFailure(failure)
    }

    fun publishIgnored(id: String) {
        kafkaTemplate.send("orders", id)
    }

    fun publishObserved(id: String) {
        kafkaTemplate.send("orders", id).whenComplete { _, failure ->
            if (failure != null) logger.error("Kafka publish failed", failure)
        }
    }

    fun transactionBoundaries(id: String) {
        auditInNewTransaction(id)
        saveRequired(id)
        requireExistingTransaction(id)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun auditInNewTransaction(id: String) = repository.save(id)

    @Transactional
    fun saveRequired(id: String) = repository.save(id)

    @Transactional(propagation = Propagation.MANDATORY)
    fun requireExistingTransaction(id: String) = repository.save(id)

    @Transactional
    fun rollbackSuppressed(id: String) {
        try {
            repository.save(id)
        } catch (failure: RuntimeException) {
            logger.error("Transactional save failed", failure)
        }
    }

    @Transactional
    fun rollbackPropagated(id: String) {
        try {
            repository.save(id)
        } catch (failure: RuntimeException) {
            throw failure
        }
    }

    fun invokeUnmanaged(id: String) = unmanagedWorker.execute(id)

    @Scheduled(cron = "0 0 * * * *")
    fun scheduledQuietly() {
        try {
            client.refresh()
        } catch (failure: RuntimeException) {
            // the scheduler sees a successful execution
        }
    }

    @Scheduled(cron = "0 30 * * * *")
    fun scheduledPropagated() {
        try {
            client.refresh()
        } catch (failure: RuntimeException) {
            throw failure
        }
    }
}

class UnmanagedWorker {
    @Transactional
    fun execute(id: String): String = id
}
