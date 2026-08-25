package example.java.parity;

import java.util.List;
import java.util.Optional;

/**
 * Fixture: exercises LOCK_NOT_RELEASED, THREAD_LOCAL_LEAK, FUTURE_GET_WITHOUT_TIMEOUT,
 * BLOCKING_CALL_IN_REACTIVE_CONTEXT, N_PLUS_ONE_QUERY_RISK, and OPTIONAL_GET_WITHOUT_CHECK.
 */
@Service
class JavaConcurrencyAndSafetyService {

    private final Lock lock;
    private final ThreadLocal<String> requestId;
    private final CompletableFuture<String> pendingResult;
    private final JavaRepository repository;
    private final Logger logger;

    JavaConcurrencyAndSafetyService(Lock lock, ThreadLocal<String> requestId,
            CompletableFuture<String> pendingResult, JavaRepository repository, Logger logger) {
        this.lock = lock;
        this.requestId = requestId;
        this.pendingResult = pendingResult;
        this.repository = repository;
        this.logger = logger;
    }

    // ── LOCK_NOT_RELEASED ────────────────────────────────────────────────────

    /** lock() acquired but unlock() is only on the happy path — not in finally. */
    void processUnsafe(String id) throws Exception {
        lock.lock();
        repository.save(id);
        lock.unlock(); // not in finally
    }

    /** Safe pattern for reference — should NOT be flagged. */
    void processSafe(String id) throws Exception {
        lock.lock();
        try {
            repository.save(id);
        } finally {
            lock.unlock();
        }
    }

    // ── THREAD_LOCAL_LEAK ────────────────────────────────────────────────────

    /** set() without remove() — leaks across thread-pool reuse. */
    void handleRequest(String id) {
        requestId.set(id);
        repository.save(id);
        // remove() never called
    }

    /** Safe: set() paired with remove() in finally. */
    void handleRequestSafe(String id) {
        requestId.set(id);
        try {
            repository.save(id);
        } finally {
            requestId.remove();
        }
    }

    // ── FUTURE_GET_WITHOUT_TIMEOUT ───────────────────────────────────────────

    /** get() with no timeout — blocks indefinitely. */
    String awaitResultBlocking() throws Exception {
        return pendingResult.get(); // no timeout
    }

    /** join() — also blocks without timeout. */
    String awaitJoinBlocking() {
        return pendingResult.join();
    }

    /** Safe: get(long, TimeUnit). */
    String awaitWithTimeout() throws Exception {
        return pendingResult.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    // ── BLOCKING_CALL_IN_REACTIVE_CONTEXT ────────────────────────────────────

    /** @NonBlocking method that calls Thread.sleep — stalls the event loop. */
    @NonBlocking
    void reactiveHandlerBlockingSleep(String id) throws Exception {
        Thread.sleep(500); // blocks event loop
        repository.save(id);
    }

    /** @Incoming reactive consumer that awaits on a latch. */
    @Incoming("orders")
    void consumeBlocking(String id) throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        latch.await(); // blocks event loop thread
    }

    // ── N_PLUS_ONE_QUERY_RISK ────────────────────────────────────────────────

    /** Calls repository.findById() inside a for loop — N+1 pattern. */
    void enrichOrders(List<String> ids) {
        for (String id : ids) {
            repository.findById(id); // inside loop → N+1
        }
    }

    /** Safe: bulk-load outside the loop. */
    void enrichOrdersSafe(List<String> ids) {
        List<String> orders = repository.findAllById(ids);
        for (String order : orders) {
            logger.info("Processing {}", order);
        }
    }

    // ── OPTIONAL_GET_WITHOUT_CHECK ───────────────────────────────────────────

    /** Optional.get() without isPresent() check — may throw NoSuchElementException. */
    String loadUnchecked(String id) {
        Optional<String> result = repository.findOptional(id);
        return result.get(); // no isPresent() check
    }

    /** Safe: use orElse. */
    String loadSafe(String id) {
        return repository.findOptional(id).orElse("default");
    }
}
