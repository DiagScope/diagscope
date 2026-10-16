package example.java.parity;

/** Fixture for VERTX_EXECUTE_BLOCKING_FAILURE_IGNORED. */
class JavaVertxBlockingFixture {
    private io.vertx.core.Vertx vertx;

    int load() {
        return 1;
    }

    // TRIGGERS: the returned future is discarded
    void discardsTheFuture() {
        vertx.executeBlocking(() -> load());
    }

    // TRIGGERS: the chain only registers onSuccess
    void onlyHandlesSuccess() {
        vertx.executeBlocking(() -> load()).onSuccess(value -> System.out.println(value));
    }

    // TRIGGERS: the callback form reads the result without checking the failure
    void callbackIgnoresFailure() {
        vertx.executeBlocking(promise -> promise.complete(load()), result -> System.out.println(result.result()));
    }

    // SAFE: failure handled in the chain
    void handlesTheFailure() {
        vertx.executeBlocking(() -> load())
                .onSuccess(value -> System.out.println(value))
                .onFailure(error -> error.printStackTrace());
    }

    // SAFE: the callback checks the failure
    void callbackChecksFailure() {
        vertx.executeBlocking(promise -> promise.complete(load()), result -> {
            if (result.failed()) {
                result.cause().printStackTrace();
            }
        });
    }

    // SAFE: the future is handed to the caller
    Object returnsTheFuture() {
        return vertx.executeBlocking(() -> load());
    }
}
