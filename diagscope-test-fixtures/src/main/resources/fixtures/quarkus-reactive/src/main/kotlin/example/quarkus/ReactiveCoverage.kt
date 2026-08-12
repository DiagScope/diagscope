package example.quarkus

annotation class Incoming(val value: String)
annotation class Scheduled(val every: String = "", val identity: String = "")

class Logger { fun error(message: String, failure: Throwable) {} }

class Uni<T> {
    fun onFailure(): Uni<T> = this
    fun recoverWithItem(item: T): Uni<T> = this
    fun recoverWithItem(recovery: (Throwable) -> T): Uni<T> = this
    fun subscribe(): Subscription<T> = Subscription()
}

class Subscription<T> {
    fun with(item: (T) -> Unit) {}
    fun with(item: (T) -> Unit, failure: (Throwable) -> Unit) {}
}

class OrdersConsumer {
    private val recovery = MutinyRecovery()

    @Incoming("orders")
    fun consume(order: String) {
        try {
            process(order)
            recovery.silentlyRecovers()
            recovery.silentlySubscribes()
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
