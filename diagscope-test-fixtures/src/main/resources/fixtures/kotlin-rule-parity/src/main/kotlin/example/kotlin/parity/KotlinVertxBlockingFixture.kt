package example.kotlin.parity

/** Fixture for VERTX_EXECUTE_BLOCKING_FAILURE_IGNORED. */
class KotlinVertxBlockingFixture(private val vertx: io.vertx.core.Vertx) {
    fun load(): Int = 1

    // TRIGGERS: the returned future is discarded
    fun discardsTheFuture() {
        vertx.executeBlocking<Int> { load() }
    }

    // TRIGGERS: the chain only registers onSuccess
    fun onlyHandlesSuccess() {
        vertx.executeBlocking<Int> { load() }.onSuccess { println(it) }
    }

    // SAFE: failure handled in the chain
    fun handlesTheFailure() {
        vertx.executeBlocking<Int> { load() }
            .onSuccess { println(it) }
            .onFailure { it.printStackTrace() }
    }

    // SAFE: the future is handed to the caller
    fun returnsTheFuture(): Any = vertx.executeBlocking<Int> { load() }
}
