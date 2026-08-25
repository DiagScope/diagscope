package example.kotlin.parity

import java.util.Optional

/**
 * Fixture: exercises LOCK_NOT_RELEASED, THREAD_LOCAL_LEAK, FUTURE_GET_WITHOUT_TIMEOUT,
 * BLOCKING_CALL_IN_REACTIVE_CONTEXT, N_PLUS_ONE_QUERY_RISK, and OPTIONAL_GET_WITHOUT_CHECK.
 */
@Service
class KotlinConcurrencyAndSafetyService(
    private val lock: Lock,
    private val requestId: ThreadLocal<String>,
    private val pendingResult: CompletableFuture<String>,
    private val repository: KotlinRepository,
    private val logger: Logger
) {

    // ── LOCK_NOT_RELEASED ────────────────────────────────────────────────────

    /** lock() acquired but unlock() is only on the happy path — not in finally. */
    fun processUnsafe(id: String) {
        lock.lock()
        repository.save(id)
        lock.unlock() // not in finally
    }

    /** Safe pattern for reference — should NOT be flagged. */
    fun processSafe(id: String) {
        lock.lock()
        try {
            repository.save(id)
        } finally {
            lock.unlock()
        }
    }

    // ── THREAD_LOCAL_LEAK ────────────────────────────────────────────────────

    /** set() without remove() — leaks across thread-pool reuse. */
    fun handleRequest(id: String) {
        requestId.set(id)
        repository.save(id)
        // remove() never called
    }

    /** Safe: set() paired with remove() in finally. */
    fun handleRequestSafe(id: String) {
        requestId.set(id)
        try {
            repository.save(id)
        } finally {
            requestId.remove()
        }
    }

    // ── FUTURE_GET_WITHOUT_TIMEOUT ───────────────────────────────────────────

    /** get() with no timeout — blocks indefinitely. */
    @Throws(Exception::class)
    fun awaitResultBlocking(): String = pendingResult.get() // no timeout

    /** join() — also blocks without timeout. */
    fun awaitJoinBlocking(): String = pendingResult.join()

    /** Safe: get(long, TimeUnit). */
    @Throws(Exception::class)
    fun awaitWithTimeout(): String = pendingResult.get(5, java.util.concurrent.TimeUnit.SECONDS)

    // ── BLOCKING_CALL_IN_REACTIVE_CONTEXT ────────────────────────────────────

    /** @NonBlocking method that calls Thread.sleep — stalls the event loop. */
    @NonBlocking
    @Throws(Exception::class)
    fun reactiveHandlerBlockingSleep(id: String) {
        Thread.sleep(500) // blocks event loop
        repository.save(id)
    }

    /** @Incoming reactive consumer that calls Thread.sleep. */
    @Incoming("orders")
    @Throws(Exception::class)
    fun consumeBlocking(id: String) {
        val latch = java.util.concurrent.CountDownLatch(1)
        latch.await() // blocks event loop thread
    }

    // ── N_PLUS_ONE_QUERY_RISK ────────────────────────────────────────────────

    /** Calls repository.findById() inside a for loop — N+1 pattern. */
    fun enrichOrders(ids: List<String>) {
        for (id in ids) {
            repository.findById(id) // inside loop → N+1
        }
    }

    /** Safe: bulk-load outside the loop. */
    fun enrichOrdersSafe(ids: List<String>) {
        val orders = repository.findAllById(ids)
        for (order in orders) {
            logger.info("Processing {}", order)
        }
    }

    // ── OPTIONAL_GET_WITHOUT_CHECK ───────────────────────────────────────────

    /** Optional.get() without isPresent() check — may throw NoSuchElementException. */
    fun loadUnchecked(id: String): String {
        val result: Optional<String> = repository.findOptional(id)
        return result.get() // no isPresent() check
    }

    /** Safe: use orElse. */
    fun loadSafe(id: String): String = repository.findOptional(id).orElse("default")
}
