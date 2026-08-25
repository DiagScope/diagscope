package example.java.parity;

import java.util.List;

/**
 * Fixture: exercises INTERRUPTED_EXCEPTION_SWALLOWED, COMPLETABLEFUTURE_EXCEPTION_NOT_HANDLED,
 * EXECUTOR_NOT_SHUTDOWN, ASYNC_ON_PRIVATE_METHOD, SCHEDULED_EXCEPTION_NOT_HANDLED,
 * BULK_OPERATION_IN_LOOP, SPAN_NOT_CLOSED, and KAFKA_DEAD_LETTER_NOT_CONFIGURED.
 */
@Service
class JavaNewRulesFixture {

    private final JavaRepository repository;
    private final Logger logger;
    private final Tracer tracer;

    JavaNewRulesFixture(JavaRepository repository, Logger logger, Tracer tracer) {
        this.repository = repository;
        this.logger = logger;
        this.tracer = tracer;
    }

    // ── INTERRUPTED_EXCEPTION_SWALLOWED ──────────────────────────────────────

    /** InterruptedException caught but interrupt flag never restored. */
    void waitForData() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            logger.warn("Wait interrupted, continuing anyway");
            // Thread.currentThread().interrupt() never called
        }
    }

    /** Safe: restores the interrupt flag before returning. */
    void waitForDataSafe() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Wait interrupted, stopping");
        }
    }

    // ── COMPLETABLEFUTURE_EXCEPTION_NOT_HANDLED ───────────────────────────────

    /** supplyAsync without .exceptionally(), .handle(), or .whenComplete(). */
    void processAsync(String id) {
        CompletableFuture.supplyAsync(() -> repository.findById(id));
        // exception silently discarded
    }

    /** Safe: exception handled via exceptionally(). */
    void processAsyncSafe(String id) {
        CompletableFuture.supplyAsync(() -> repository.findById(id))
                .exceptionally(ex -> {
                    logger.error("Async processing failed for {}", id, ex);
                    return null;
                });
    }

    // ── EXECUTOR_NOT_SHUTDOWN ─────────────────────────────────────────────────

    /** ExecutorService created locally, never shut down. */
    void processInParallel(List<String> ids) {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        for (String id : ids) {
            executor.submit(() -> repository.save(id));
        }
        // executor.shutdown() never called — threads leak
    }

    /** Safe: shutdown called in finally. */
    void processInParallelSafe(List<String> ids) {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            for (String id : ids) {
                executor.submit(() -> repository.save(id));
            }
        } finally {
            executor.shutdown();
        }
    }

    // ── ASYNC_ON_PRIVATE_METHOD ───────────────────────────────────────────────

    /** @Async on private method — proxy cannot intercept, runs synchronously. */
    @Async
    private void sendEmailAsync(String recipient) {
        logger.info("Sending email to {}", recipient);
    }

    /** Safe: @Async on public method — proxy can intercept. */
    @Async
    public void sendEmailAsyncPublic(String recipient) {
        logger.info("Sending email to {}", recipient);
    }

    /** Triggers the private @Async method so it appears in a flow from the controller. */
    void notifyAsync(String recipient) {
        sendEmailAsync(recipient);
    }

    // ── SCHEDULED_EXCEPTION_NOT_HANDLED ──────────────────────────────────────

    /** @Scheduled method with external call but no try/catch — exception kills future executions. */
    @Scheduled(fixedDelay = 60000)
    void generateDailyReport() {
        repository.computeStats(); // may throw — not caught
    }

    /** Safe: @Scheduled with catch block protecting the full body. */
    @Scheduled(fixedDelay = 60000)
    void generateDailyReportSafe() {
        try {
            repository.computeStats();
        } catch (Exception e) {
            logger.error("Daily report generation failed", e);
        }
    }

    // ── BULK_OPERATION_IN_LOOP ────────────────────────────────────────────────

    /** repository.save() inside a for loop — N separate database round-trips. */
    void saveAll(List<String> items) {
        for (String item : items) {
            repository.save(item); // one round-trip per item
        }
    }

    /** Safe: collect all items and call saveAll() once. */
    void saveAllSafe(List<String> items) {
        repository.saveAll(items);
    }

    // ── SPAN_NOT_CLOSED ───────────────────────────────────────────────────────

    /** Span started but end() not guarded by finally — leaks on exception paths. */
    void tracedOperation(String id) {
        Span span = tracer.startSpan("tracedOperation");
        repository.save(id); // may throw — span leaks
        span.end(); // only on happy path
    }

    /** Safe: span.end() in finally block. */
    void tracedOperationSafe(String id) {
        Span span = tracer.startSpan("tracedOperation");
        try {
            repository.save(id);
        } finally {
            span.end();
        }
    }
}

// ── KAFKA_DEAD_LETTER_NOT_CONFIGURED ─────────────────────────────────────────

/** @KafkaListener without errorHandler or DLT recoverer — failed messages are silently discarded. */
@Service
class JavaNewRulesKafkaListener {

    private final JavaRepository repository;
    private final Logger logger;

    JavaNewRulesKafkaListener(JavaRepository repository, Logger logger) {
        this.repository = repository;
        this.logger = logger;
    }

    @KafkaListener(topics = "payments")
    void onPayment(String payload) {
        repository.save(payload);
        // No errorHandler, no DeadLetterPublishingRecoverer
    }

    /** Safe: errorHandler attribute set. */
    @KafkaListener(topics = "refunds", errorHandler = "myErrorHandler")
    void onRefund(String payload) {
        repository.save(payload);
    }
}

/**
 * Controller that triggers every new rule on each request, making all fixture methods
 * reachable from a flow entrypoint.
 */
@RestController
class JavaNewRulesController {

    private final JavaNewRulesFixture fixture;

    JavaNewRulesController(JavaNewRulesFixture fixture) {
        this.fixture = fixture;
    }

    @GetMapping("/new-rules")
    String trigger(String id, List<String> items) {
        fixture.waitForData();              // → INTERRUPTED_EXCEPTION_SWALLOWED
        fixture.processAsync(id);           // → COMPLETABLEFUTURE_EXCEPTION_NOT_HANDLED
        fixture.processInParallel(items);   // → EXECUTOR_NOT_SHUTDOWN
        fixture.notifyAsync(id);            // → ASYNC_ON_PRIVATE_METHOD (via wrapper)
        fixture.saveAll(items);             // → BULK_OPERATION_IN_LOOP
        fixture.tracedOperation(id);        // → SPAN_NOT_CLOSED
        return id;
    }
}
