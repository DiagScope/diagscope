package example.quarkus;

@interface Incoming { String value(); }
@interface Scheduled { String every() default ""; String identity() default ""; }

class Logger { void error(String message, Throwable failure) { } }

class Uni<T> {
    Uni<T> onFailure() { return this; }
    Uni<T> recoverWithItem(T item) { return this; }
    Uni<T> recoverWithItem(java.util.function.Function<Throwable, T> recovery) { return this; }
    Subscription<T> subscribe() { return new Subscription<>(); }
}

class Subscription<T> {
    void with(java.util.function.Consumer<T> item) { }
    void with(java.util.function.Consumer<T> item, java.util.function.Consumer<Throwable> failure) { }
}

class OrdersConsumer {
    private final MutinyRecovery recovery = new MutinyRecovery();

    @Incoming("orders")
    void consume(String order) {
        try {
            process(order);
            recovery.silentlyRecovers();
            recovery.silentlySubscribes();
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
