package example.kotlin.parity

/**
 * Quarkus reactive parity coverage: a silent reactive consumer plus Mutiny recovery and
 * subscription callbacks with and without failure observation.
 */
class KotlinReactiveConsumer(
    private val repository: KotlinRepository,
    private val pipeline: MutinyPipeline
) {
    @Incoming("orders")
    fun consume(payload: String) {
        try {
            repository.save(payload)
            pipeline.recoverSilently()
            pipeline.subscribeSilently()
        } catch (failure: RuntimeException) {
        }
    }

    @Incoming("audited-orders")
    fun consumeAudited(payload: String) {
        try {
            repository.save(payload)
            pipeline.recoverObserved()
            pipeline.subscribeObserved()
        } catch (failure: RuntimeException) {
            throw IllegalStateException("order processing failed for $payload", failure)
        }
    }
}

class MutinyPipeline(
    private val uni: Uni<String>,
    private val logger: Logger
) {
    fun recoverSilently(): String {
        uni.onFailure().recoverWithItem("fallback")
        return "fallback"
    }

    fun recoverObserved(): String {
        uni.onFailure().recoverWithItem { failure ->
            logger.error("Reactive lookup failed", failure)
            "fallback"
        }
        return "fallback"
    }

    fun subscribeSilently() {
        uni.subscribe().with { item -> logger.info("received {}", item) }
    }

    fun subscribeObserved() {
        uni.subscribe().with(
            { item -> logger.info("received {}", item) },
            { failure -> logger.error("Reactive subscription failed", failure) }
        )
    }
}
