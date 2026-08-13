package example.java.parity;

/**
 * Quarkus reactive parity coverage: a silent reactive consumer plus Mutiny recovery and
 * subscription callbacks with and without failure observation.
 */
class JavaReactiveConsumer {
    private final JavaRepository repository;
    private final MutinyPipeline pipeline;

    JavaReactiveConsumer(JavaRepository repository, MutinyPipeline pipeline) {
        this.repository = repository;
        this.pipeline = pipeline;
    }

    @Incoming("orders")
    void consume(String payload) {
        try {
            repository.save(payload);
            pipeline.recoverSilently();
            pipeline.subscribeSilently();
        } catch (RuntimeException failure) {
        }
    }

    @Incoming("audited-orders")
    void consumeAudited(String payload) {
        try {
            repository.save(payload);
            pipeline.recoverObserved();
            pipeline.subscribeObserved();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("order processing failed for " + payload, failure);
        }
    }
}

class MutinyPipeline {
    private final Uni<String> uni;
    private final Logger logger;

    MutinyPipeline(Uni<String> uni, Logger logger) {
        this.uni = uni;
        this.logger = logger;
    }

    String recoverSilently() {
        uni.onFailure().recoverWithItem("fallback");
        return "fallback";
    }

    String recoverObserved() {
        uni.onFailure().recoverWithItem(failure -> {
            logger.error("Reactive lookup failed", failure);
            return "fallback";
        });
        return "fallback";
    }

    void subscribeSilently() {
        uni.subscribe().with(item -> logger.info("received {}", item));
    }

    void subscribeObserved() {
        uni.subscribe().with(
                item -> logger.info("received {}", item),
                failure -> logger.error("Reactive subscription failed", failure));
    }
}
