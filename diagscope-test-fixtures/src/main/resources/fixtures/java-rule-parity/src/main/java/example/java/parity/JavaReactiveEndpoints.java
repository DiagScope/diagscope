package example.java.parity;

import io.smallrye.mutiny.Uni;

/**
 * Fixture: exercises BLOCKING_CALL_IN_REACTIVE_CONTEXT where the event loop is inferred from a
 * reactive return type and followed into helpers, instead of declared with an annotation.
 */
@Path("/reactive")
class JavaReactiveEndpoints {

    /** Uni return type marks the event loop; awaiting the Uni blocks it. */
    @GET
    @Path("/await")
    Uni<String> awaitOnEventLoop(Uni<String> pending) {
        String value = pending.await().indefinitely(); // blocks event loop thread
        return Uni.createFrom().item(value);
    }

    /** Safe: @Blocking runs on a worker thread, where awaiting is allowed. */
    @GET
    @Blocking
    @Path("/worker")
    Uni<String> awaitOnWorker(Uni<String> pending) {
        String value = pending.await().indefinitely();
        return Uni.createFrom().item(value);
    }

    /** The helper runs on the event loop because its caller does. */
    @GET
    @Path("/delegate")
    Uni<String> delegateToHelper(String id) {
        return Uni.createFrom().item(slowHelper(id));
    }

    String slowHelper(String id) {
        try {
            Thread.sleep(100); // blocks event loop thread via delegateToHelper
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return id;
    }

    /** Safe: the helper is reached only through a @Blocking method. */
    @GET
    @Blocking
    @Path("/worker-delegate")
    Uni<String> delegateToWorkerHelper(String id) {
        return Uni.createFrom().item(workerHelper(id));
    }

    String workerHelper(String id) {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return id;
    }
}
