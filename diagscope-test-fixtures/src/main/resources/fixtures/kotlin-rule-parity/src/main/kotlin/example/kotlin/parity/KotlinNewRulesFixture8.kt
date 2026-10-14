package example.kotlin.parity

import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

/**
 * Fixture: covers VIRTUAL_THREAD_WITH_SYNCHRONIZED, COMPLETABLE_FUTURE_IN_REACTIVE_CONTEXT,
 * TRANSACTIONAL_EVENT_LISTENER_MISSING_PHASE, KAFKA_CONSUMER_GROUP_HARDCODED,
 * CLOCK_DEPENDENCY_NOT_INJECTED, UNGUARDED_THREAD_CREATION, DRIVER_MANAGER_DATASOURCE_USED.
 */

// ── VIRTUAL_THREAD_WITH_SYNCHRONIZED ─────────────────────────────────────────

@Service
open class KotlinWave9VirtualThreadService {

    /** VIRTUAL_THREAD_WITH_SYNCHRONIZED: @Synchronized Kotlin method also with @RunOnVirtualThread. */
    @RunOnVirtualThread
    @Synchronized
    open fun processOrder(orderId: String) { // → VIRTUAL_THREAD_WITH_SYNCHRONIZED
        // @Synchronized pins the carrier thread — defeats virtual-thread scalability
    }

    /** Safe: ReentrantLock instead of @Synchronized — carrier thread is not pinned. */
    @RunOnVirtualThread
    open fun processOrderSafe(orderId: String) {
        // lock.withLock { ... }
    }
}

// ── COMPLETABLE_FUTURE_IN_REACTIVE_CONTEXT ────────────────────────────────────

@RestController
open class KotlinWave9ReactiveController {

    /** COMPLETABLE_FUTURE_IN_REACTIVE_CONTEXT: supplyAsync without executor in a Mono-returning method. */
    @GetMapping("/wave9/order/{id}")
    open fun getOrder(id: String): Mono<String> { // reactive return type
        return Mono.fromFuture(
            CompletableFuture.supplyAsync { "order-$id" } // → COMPLETABLE_FUTURE_IN_REACTIVE_CONTEXT (no executor)
        )
    }

    /** Safe: explicit executor provided — does not use ForkJoinPool.commonPool(). */
    @GetMapping("/wave9/order/{id}/safe")
    open fun getOrderSafe(id: String): Mono<String> {
        return Mono.fromFuture(
            CompletableFuture.supplyAsync({ "order-$id" }, taskExecutor)
        )
    }
}

// ── TRANSACTIONAL_EVENT_LISTENER_MISSING_PHASE ────────────────────────────────

@Service
open class KotlinWave9OrderEventListener {

    /** TRANSACTIONAL_EVENT_LISTENER_MISSING_PHASE: no phase attribute declared. */
    @TransactionalEventListener
    open fun onOrderPlaced(event: Any) { // → TRANSACTIONAL_EVENT_LISTENER_MISSING_PHASE
        // default phase AFTER_COMMIT is hidden; session is closed when this runs
    }

    /** Safe: explicit phase makes the contract visible to the reader. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    open fun onOrderPlacedSafe(event: Any) {
        // phase is explicit — no surprise
    }
}

// ── KAFKA_CONSUMER_GROUP_HARDCODED ────────────────────────────────────────────

@Service
open class KotlinWave9KafkaConsumer {

    /** KAFKA_CONSUMER_GROUP_HARDCODED: literal string in groupId. */
    @KafkaListener(topics = ["\${kafka.topics.orders}"], groupId = "order-processing-group") // → KAFKA_CONSUMER_GROUP_HARDCODED
    open fun consumeOrder(message: String) {
        // hardcoded group ID causes cross-environment offset pollution
    }

    /** Safe: groupId uses a property placeholder. */
    @KafkaListener(topics = ["\${kafka.topics.orders}"], groupId = "\${kafka.consumer.orders.group-id}")
    open fun consumeOrderSafe(message: String) {
        // group ID externalized to config — safe
    }
}

// ── CLOCK_DEPENDENCY_NOT_INJECTED ─────────────────────────────────────────────

@Service
open class KotlinWave9OrderExpiryService {

    /** CLOCK_DEPENDENCY_NOT_INJECTED: LocalDateTime.now() with no Clock argument. */
    open fun isExpired(order: Any): Boolean { // → CLOCK_DEPENDENCY_NOT_INJECTED
        val now = LocalDateTime.now() // cannot be controlled in tests
        return now.isAfter(expiryOf(order))
    }

    /** Safe: uses injected Clock — tests can freeze time with Clock.fixed(). */
    open fun isExpiredSafe(order: Any, clock: java.time.Clock): Boolean {
        val now = LocalDateTime.now(clock)
        return now.isAfter(expiryOf(order))
    }

    private fun expiryOf(order: Any): LocalDateTime = LocalDateTime.now()
}

// ── UNGUARDED_THREAD_CREATION ─────────────────────────────────────────────────

@Service
open class KotlinWave9ReportGeneratorService {

    /** UNGUARDED_THREAD_CREATION: new Thread() in a Spring bean method outside @Bean. */
    open fun generateReportAsync(task: Runnable) {
        val thread = Thread(task) // → UNGUARDED_THREAD_CREATION
        thread.start() // no pool, no lifecycle, no shutdown hook
    }

    /** Safe: submits to an injected TaskExecutor — Spring manages lifecycle. */
    open fun generateReportAsyncSafe(task: Runnable) {
        taskExecutor.execute(task)
    }
}

// ── DRIVER_MANAGER_DATASOURCE_USED ────────────────────────────────────────────

@Service
open class KotlinWave9DataSourceService {

    /** DRIVER_MANAGER_DATASOURCE_USED: no-pool DataSource created outside a @Bean factory. */
    open fun initLegacyConnection() {
        val ds = DriverManagerDataSource() // → DRIVER_MANAGER_DATASOURCE_USED
        ds.setUrl("jdbc:postgresql://localhost/orders")
        // opens a new JDBC connection on every getConnection() call — no pooling
    }

    /** Safe: injects a HikariCP DataSource bean from the application context. */
    open fun queryWithPooledDs(dataSource: javax.sql.DataSource) {
        // uses the injected HikariCP pool — no new connection per call
    }
}
