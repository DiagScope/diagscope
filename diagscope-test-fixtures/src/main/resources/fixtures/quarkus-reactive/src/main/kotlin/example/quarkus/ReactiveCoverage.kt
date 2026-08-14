package example.quarkus

annotation class Incoming(val value: String)
annotation class Scheduled(val every: String = "", val identity: String = "")

class Logger { fun error(message: String, failure: Throwable?) {} }

class Uni<T> {
    fun onFailure(): Uni<T> = this
    fun onFailure(type: Class<out Throwable>): Uni<T> = this
    fun invoke(observer: (Throwable) -> Unit): Uni<T> = this
    fun recoverWithItem(item: T): Uni<T> = this
    fun recoverWithItem(recovery: (Throwable) -> T): Uni<T> = this
    fun recoverWithNull(): Uni<T> = this
    fun subscribe(): Subscription<T> = Subscription()
}

class Multi<T> {
    fun onFailure(): Multi<T> = this
    fun recoverWithMulti(fallback: Multi<T>): Multi<T> = this
    fun recoverWithCompletion(): Multi<T> = this
    fun subscribe(): Subscription<T> = Subscription()
}

class Subscription<T> {
    fun with(item: (T) -> Unit) {}
    fun with(item: (T) -> Unit, failure: (Throwable) -> Unit) {}
}

class OrdersConsumer {
    private val recovery = MutinyRecovery()
    private val variants = MutinyLambdaVariants()

    @Incoming("orders")
    fun consume(order: String) {
        try {
            process(order)
            recovery.silentlyRecovers()
            recovery.silentlySubscribes()
            variants.recoversWithMethodReference()
            variants.recoversTypedFailureSilently()
            variants.recoversMultiSilently()
            variants.logsThenRecovers()
            variants.subscribesWithMethodReference()
            variants.subscribesWithTypedLambda()
            variants.subscribesMultiWithSingleCallback()
            variants.subscribesWithFailureMethodReference()
            variants.subscribesWithTypedFailureLambda()
        } catch (failure: Exception) {
        }
    }

    @Incoming("audited-orders")
    fun audited(order: String) {
        try {
            process(order)
        } catch (failure: Exception) {
            throw IllegalStateException(failure)
        }
    }

    private fun process(order: String) {}
}

class Jobs {
    @Scheduled(every = "30s", identity = "orders-refresh")
    fun refresh() {
        try {
            process()
        } catch (failure: Exception) {
        }
    }

    private fun process() {}
}

class MutinyRecovery {
    private val uni = Uni<String>()
    private val logger = Logger()

    fun silentlyRecovers(): String {
        uni.onFailure().recoverWithItem("fallback")
        return "fallback"
    }

    fun recordsFailure(): String {
        uni.onFailure().recoverWithItem { failure ->
            logger.error("lookup failed", failure)
            "fallback"
        }
        return "fallback"
    }

    fun silentlySubscribes() {
        uni.subscribe().with { item -> logger.error("received $item", RuntimeException()) }
    }

    fun recordsSubscriptionFailure() {
        uni.subscribe().with({ }, { failure -> logger.error("lookup failed", failure) })
    }
}

/** Lambda and operator shapes that the Mutiny rules must classify consistently. */
class MutinyLambdaVariants {
    private val uni = Uni<String>()
    private val multi = Multi<String>()
    private val logger = Logger()

    // Positive, but low confidence: the recovery body lives behind a method reference.
    fun recoversWithMethodReference(): String {
        uni.onFailure().recoverWithItem(this::fallback)
        return "fallback"
    }

    // Positive: a typed onFailure(...) filter still drops the failure.
    fun recoversTypedFailureSilently(): String {
        uni.onFailure(IllegalStateException::class.java).recoverWithNull()
        return "fallback"
    }

    // Positive: Multi recovery operators behave like the Uni ones.
    fun recoversMultiSilently() {
        multi.onFailure().recoverWithMulti(Multi())
    }

    // Negative: the chain observes the failure before recovering.
    fun logsThenRecovers(): String {
        uni.onFailure().invoke { failure -> logger.error("lookup failed", failure) }.recoverWithItem("fallback")
        return "fallback"
    }

    // Positive: single-callback subscription written as a method reference.
    fun subscribesWithMethodReference() {
        uni.subscribe().with(this::consume)
    }

    // Positive: single-callback subscription written as an explicitly typed lambda.
    fun subscribesWithTypedLambda() {
        uni.subscribe().with { item: String ->
            logger.error("received $item", null)
        }
    }

    // Positive: Multi subscription with only the item callback.
    fun subscribesMultiWithSingleCallback() {
        multi.subscribe().with { item -> consume(item) }
    }

    // Negative: the failure callback is a method reference.
    fun subscribesWithFailureMethodReference() {
        uni.subscribe().with(this::consume, this::report)
    }

    // Negative: explicitly typed failure callback.
    fun subscribesWithTypedFailureLambda() {
        uni.subscribe().with({ _: String -> }, { failure: Throwable -> logger.error("lookup failed", failure) })
    }

    private fun fallback(failure: Throwable): String {
        logger.error("lookup failed", failure)
        return "fallback"
    }

    private fun consume(item: String) {}

    private fun report(failure: Throwable) {
        logger.error("subscription failed", failure)
    }
}
