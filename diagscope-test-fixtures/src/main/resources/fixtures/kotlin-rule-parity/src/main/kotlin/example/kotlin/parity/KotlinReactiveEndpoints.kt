package example.kotlin.parity

import io.smallrye.mutiny.Uni

/**
 * Fixture: exercises BLOCKING_CALL_IN_REACTIVE_CONTEXT where the event loop is inferred from a
 * reactive return type and followed into helpers, instead of declared with an annotation.
 */
@Path("/reactive")
class KotlinReactiveEndpoints {

    /** Uni return type marks the event loop; awaiting the Uni blocks it. */
    @GET
    @Path("/await")
    fun awaitOnEventLoop(pending: Uni<String>): Uni<String> {
        val value = pending.await().indefinitely() // blocks event loop thread
        return Uni.createFrom().item(value)
    }

    /** Safe: @Blocking runs on a worker thread, where awaiting is allowed. */
    @GET
    @Blocking
    @Path("/worker")
    fun awaitOnWorker(pending: Uni<String>): Uni<String> {
        val value = pending.await().indefinitely()
        return Uni.createFrom().item(value)
    }

    /** The helper runs on the event loop because its caller does. */
    @GET
    @Path("/delegate")
    fun delegateToHelper(id: String): Uni<String> = Uni.createFrom().item(slowHelper(id))

    fun slowHelper(id: String): String {
        Thread.sleep(100) // blocks event loop thread via delegateToHelper
        return id
    }

    /** Safe: the helper is reached only through a @Blocking method. */
    @GET
    @Blocking
    @Path("/worker-delegate")
    fun delegateToWorkerHelper(id: String): Uni<String> = Uni.createFrom().item(workerHelper(id))

    fun workerHelper(id: String): String {
        Thread.sleep(100)
        return id
    }
}
