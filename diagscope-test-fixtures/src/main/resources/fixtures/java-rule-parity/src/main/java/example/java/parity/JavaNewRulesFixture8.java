package example.java.parity;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * Fixture: covers VIRTUAL_THREAD_WITH_SYNCHRONIZED, COMPLETABLE_FUTURE_IN_REACTIVE_CONTEXT,
 * TRANSACTIONAL_EVENT_LISTENER_MISSING_PHASE, KAFKA_CONSUMER_GROUP_HARDCODED,
 * CLOCK_DEPENDENCY_NOT_INJECTED, UNGUARDED_THREAD_CREATION, DRIVER_MANAGER_DATASOURCE_USED.
 */

// ── VIRTUAL_THREAD_WITH_SYNCHRONIZED ─────────────────────────────────────────

@Service
class JavaWave9VirtualThreadService {

    /** VIRTUAL_THREAD_WITH_SYNCHRONIZED: synchronized method also annotated with @RunOnVirtualThread. */
    @RunOnVirtualThread
    public synchronized void processOrder(String orderId) { // → VIRTUAL_THREAD_WITH_SYNCHRONIZED
        // synchronized pins the carrier thread — defeats virtual-thread scalability
    }

    /** Safe: uses ReentrantLock instead of synchronized — does not pin the carrier. */
    @RunOnVirtualThread
    public void processOrderSafe(String orderId) {
        // lock.lock(); try { ... } finally { lock.unlock(); }
    }
}

// ── COMPLETABLE_FUTURE_IN_REACTIVE_CONTEXT ────────────────────────────────────

@RestController
class JavaWave9ReactiveController {

    /** COMPLETABLE_FUTURE_IN_REACTIVE_CONTEXT: supplyAsync without executor inside a Mono-returning method. */
    @GetMapping("/wave9/order/{id}")
    public Mono<String> getOrder(String id) { // reactive return type
        return Mono.fromFuture(
                CompletableFuture.supplyAsync(() -> "order-" + id) // → COMPLETABLE_FUTURE_IN_REACTIVE_CONTEXT (no executor)
        );
    }

    /** Safe: explicit executor provided — does not use ForkJoinPool.commonPool(). */
    @GetMapping("/wave9/order/{id}/safe")
    public Mono<String> getOrderSafe(String id) {
        return Mono.fromFuture(
                CompletableFuture.supplyAsync(() -> "order-" + id, taskExecutor)
        );
    }
}

// ── TRANSACTIONAL_EVENT_LISTENER_MISSING_PHASE ────────────────────────────────

@Service
class JavaWave9OrderEventListener {

    /** TRANSACTIONAL_EVENT_LISTENER_MISSING_PHASE: no phase attribute declared. */
    @TransactionalEventListener
    public void onOrderPlaced(Object event) { // → TRANSACTIONAL_EVENT_LISTENER_MISSING_PHASE
        // default phase AFTER_COMMIT is hidden; session is closed when this runs
    }

    /** Safe: explicit phase declaration makes the contract visible. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlacedSafe(Object event) {
        // phase is explicit — no surprise
    }
}

// ── KAFKA_CONSUMER_GROUP_HARDCODED ────────────────────────────────────────────

@Service
class JavaWave9KafkaConsumer {

    /** KAFKA_CONSUMER_GROUP_HARDCODED: literal string in groupId. */
    @KafkaListener(topics = "${kafka.topics.orders}", groupId = "order-processing-group") // → KAFKA_CONSUMER_GROUP_HARDCODED
    public void consumeOrder(String message) {
        // hardcoded group ID causes cross-environment offset pollution
    }

    /** Safe: groupId uses a property placeholder. */
    @KafkaListener(topics = "${kafka.topics.orders}", groupId = "${kafka.consumer.orders.group-id}")
    public void consumeOrderSafe(String message) {
        // group ID externalized to config — safe
    }
}

// ── CLOCK_DEPENDENCY_NOT_INJECTED ─────────────────────────────────────────────

@Service
class JavaWave9OrderExpiryService {

    /** CLOCK_DEPENDENCY_NOT_INJECTED: LocalDateTime.now() with no Clock argument. */
    public boolean isExpired(Object order) { // → CLOCK_DEPENDENCY_NOT_INJECTED
        LocalDateTime now = LocalDateTime.now(); // cannot be controlled in tests
        return now.isAfter(expiryOf(order));
    }

    /** Safe: uses injected Clock — tests can freeze time with Clock.fixed(). */
    public boolean isExpiredSafe(Object order, java.time.Clock clock) {
        LocalDateTime now = LocalDateTime.now(clock);
        return now.isAfter(expiryOf(order));
    }

    private LocalDateTime expiryOf(Object order) { return LocalDateTime.now(); }
}

// ── UNGUARDED_THREAD_CREATION ─────────────────────────────────────────────────

@Service
class JavaWave9ReportGeneratorService {

    /** UNGUARDED_THREAD_CREATION: new Thread() in a Spring bean method outside @Bean. */
    public void generateReportAsync(Runnable task) {
        Thread thread = new Thread(task); // → UNGUARDED_THREAD_CREATION
        thread.start(); // no pool, no lifecycle, no shutdown hook
    }

    /** Safe: submits to an injected TaskExecutor — Spring manages lifecycle. */
    public void generateReportAsyncSafe(Runnable task) {
        taskExecutor.execute(task);
    }
}

// ── DRIVER_MANAGER_DATASOURCE_USED ────────────────────────────────────────────

@Service
class JavaWave9DataSourceService {

    /** DRIVER_MANAGER_DATASOURCE_USED: no-pool DataSource created outside a @Bean factory. */
    public void initLegacyConnection() {
        DriverManagerDataSource ds = new DriverManagerDataSource(); // → DRIVER_MANAGER_DATASOURCE_USED
        ds.setUrl("jdbc:postgresql://localhost/orders");
        // opens a new JDBC connection on every getConnection() call — no pooling
    }

    /** Safe: injects a HikariCP DataSource bean from the application context. */
    public void queryWithPooledDs(javax.sql.DataSource dataSource) {
        // uses the injected HikariCP pool — no new connection per call
    }
}
