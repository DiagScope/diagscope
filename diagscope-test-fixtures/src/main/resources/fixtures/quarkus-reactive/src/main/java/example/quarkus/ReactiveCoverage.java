package example.quarkus;

@interface Incoming { String value(); }
@interface Scheduled { String every() default ""; String identity() default ""; }

class Logger { void error(String message, Throwable failure) { } }

class Uni<T> {
    Uni<T> onFailure() { return this; }
    Uni<T> onFailure(Class<? extends Throwable> type) { return this; }
    Uni<T> invoke(java.util.function.Consumer<Throwable> observer) { return this; }
    Uni<T> recoverWithItem(T item) { return this; }
    Uni<T> recoverWithItem(java.util.function.Function<Throwable, T> recovery) { return this; }
    Uni<T> recoverWithNull() { return this; }
    Subscription<T> subscribe() { return new Subscription<>(); }
}

class Multi<T> {
    Multi<T> onFailure() { return this; }
    Multi<T> recoverWithMulti(Multi<T> fallback) { return this; }
    Multi<T> recoverWithCompletion() { return this; }
    Subscription<T> subscribe() { return new Subscription<>(); }
}

class Subscription<T> {
    void with(java.util.function.Consumer<T> item) { }
    void with(java.util.function.Consumer<T> item, java.util.function.Consumer<Throwable> failure) { }
}

class OrdersConsumer {
    private final MutinyRecovery recovery = new MutinyRecovery();
    private final MutinyLambdaVariants variants = new MutinyLambdaVariants();

    @Incoming("orders")
    void consume(String order) {
        try {
            process(order);
            recovery.silentlyRecovers();
            recovery.silentlySubscribes();
            variants.recoversWithMethodReference();
            variants.recoversTypedFailureSilently();
            variants.recoversMultiSilently();
            variants.logsThenRecovers();
            variants.subscribesWithMethodReference();
            variants.subscribesWithTypedLambda();
            variants.subscribesMultiWithSingleCallback();
            variants.subscribesWithFailureMethodReference();
            variants.subscribesWithTypedFailureLambda();
        } catch (Exception failure) {
        }
    }

    @Incoming("audited-orders")
    void audited(String order) {
        try {
            process(order);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void process(String order) { }
}

class Jobs {
    @Scheduled(every = "30s", identity = "orders-refresh")
    void refresh() {
        try {
            process();
        } catch (Exception failure) {
        }
    }

    private void process() { }
}

class MutinyRecovery {
    private final Uni<String> uni = new Uni<>();
    private final Logger logger = new Logger();

    String silentlyRecovers() {
        uni.onFailure().recoverWithItem("fallback");
        return "fallback";
    }

    String recordsFailure() {
        uni.onFailure().recoverWithItem(failure -> {
            logger.error("lookup failed", failure);
            return "fallback";
        });
        return "fallback";
    }

    void silentlySubscribes() {
        uni.subscribe().with(item -> logger.error("received " + item, null));
    }

    void recordsSubscriptionFailure() {
        uni.subscribe().with(item -> { }, failure -> logger.error("lookup failed", failure));
    }
}

/** Lambda and operator shapes that the Mutiny rules must classify consistently. */
class MutinyLambdaVariants {
    private final Uni<String> uni = new Uni<>();
    private final Multi<String> multi = new Multi<>();
    private final Logger logger = new Logger();

    // Positive, but low confidence: the recovery body lives behind a method reference.
    String recoversWithMethodReference() {
        uni.onFailure().recoverWithItem(this::fallback);
        return "fallback";
    }

    // Positive: a typed onFailure(...) filter still drops the failure.
    String recoversTypedFailureSilently() {
        uni.onFailure(IllegalStateException.class).recoverWithNull();
        return "fallback";
    }

    // Positive: Multi recovery operators behave like the Uni ones.
    void recoversMultiSilently() {
        multi.onFailure().recoverWithMulti(new Multi<>());
    }

    // Negative: the chain observes the failure before recovering.
    String logsThenRecovers() {
        uni.onFailure().invoke(failure -> logger.error("lookup failed", failure)).recoverWithItem("fallback");
        return "fallback";
    }

    // Positive: single-callback subscription written as a method reference.
    void subscribesWithMethodReference() {
        uni.subscribe().with(this::consume);
    }

    // Positive: single-callback subscription written as an explicitly typed lambda.
    void subscribesWithTypedLambda() {
        uni.subscribe().with((String item) -> {
            logger.error("received " + item, null);
        });
    }

    // Positive: Multi subscription with only the item callback.
    void subscribesMultiWithSingleCallback() {
        multi.subscribe().with(item -> consume(item));
    }

    // Negative: the failure callback is a method reference.
    void subscribesWithFailureMethodReference() {
        uni.subscribe().with(this::consume, this::report);
    }

    // Negative: explicitly typed failure callback.
    void subscribesWithTypedFailureLambda() {
        uni.subscribe().with((String item) -> { }, (Throwable failure) -> logger.error("lookup failed", failure));
    }

    private String fallback(Throwable failure) {
        logger.error("lookup failed", failure);
        return "fallback";
    }

    private void consume(String item) { }

    private void report(Throwable failure) {
        logger.error("subscription failed", failure);
    }
}
