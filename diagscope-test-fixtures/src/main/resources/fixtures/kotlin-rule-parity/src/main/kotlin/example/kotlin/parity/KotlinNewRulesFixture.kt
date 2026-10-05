package example.kotlin.parity

/**
 * Fixture: exercises INTERRUPTED_EXCEPTION_SWALLOWED, COMPLETABLEFUTURE_EXCEPTION_NOT_HANDLED,
 * EXECUTOR_NOT_SHUTDOWN, ASYNC_ON_PRIVATE_METHOD, SCHEDULED_EXCEPTION_NOT_HANDLED,
 * BULK_OPERATION_IN_LOOP, SPAN_NOT_CLOSED, KAFKA_DEAD_LETTER_NOT_CONFIGURED,
 * OUTBOX_PATTERN_MISSING, SECRET_IN_STRING_LITERAL, COROUTINE_EXCEPTION_NOT_HANDLED,
 * FLOW_EXCEPTION_NOT_CAUGHT, and BLOCKING_CALL_IN_COROUTINE.
 */
@Service
class KotlinNewRulesFixture(
    private val repository: KotlinRepository,
    private val logger: Logger,
    private val tracer: Tracer
) {

    // ── INTERRUPTED_EXCEPTION_SWALLOWED ──────────────────────────────────────

    /** InterruptedException caught but interrupt flag never restored. */
    fun waitForData() {
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            logger.warn("Wait interrupted, continuing anyway")
            // Thread.currentThread().interrupt() never called
        }
    }

    /** Safe: restores the interrupt flag before returning. */
    fun waitForDataSafe() {
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Wait interrupted, stopping")
        }
    }

    // ── COMPLETABLEFUTURE_EXCEPTION_NOT_HANDLED ───────────────────────────────

    /** supplyAsync without .exceptionally(), .handle(), or .whenComplete(). */
    fun processAsync(id: String) {
        CompletableFuture.supplyAsync { repository.findById(id) }
        // exception silently discarded
    }

    /** Safe: exception handled via exceptionally(). */
    fun processAsyncSafe(id: String) {
        CompletableFuture.supplyAsync { repository.findById(id) }
            .exceptionally { ex ->
                logger.error("Async processing failed for {}", id, ex)
                null
            }
    }

    // ── EXECUTOR_NOT_SHUTDOWN ─────────────────────────────────────────────────

    /** ExecutorService created locally, never shut down. */
    fun processInParallel(ids: List<String>) {
        val executor = Executors.newFixedThreadPool(4)
        for (id in ids) {
            executor.submit { repository.save(id) }
        }
        // executor.shutdown() never called — threads leak
    }

    /** Safe: shutdown called in finally. */
    fun processInParallelSafe(ids: List<String>) {
        val executor = Executors.newFixedThreadPool(4)
        try {
            for (id in ids) {
                executor.submit { repository.save(id) }
            }
        } finally {
            executor.shutdown()
        }
    }

    // ── ASYNC_ON_PRIVATE_METHOD ───────────────────────────────────────────────

    /** @Async on private method — proxy cannot intercept, runs synchronously. */
    @Async
    private fun sendEmailAsync(recipient: String) {
        logger.info("Sending email to {}", recipient)
    }

    /** Safe: @Async on public method — proxy can intercept. */
    @Async
    fun sendEmailAsyncPublic(recipient: String) {
        logger.info("Sending email to {}", recipient)
    }

    /** Triggers the private @Async method so it appears in a flow from the controller. */
    fun notifyAsync(recipient: String) {
        sendEmailAsync(recipient)
    }

    // ── SCHEDULED_EXCEPTION_NOT_HANDLED ──────────────────────────────────────

    /** @Scheduled method with external call but no try/catch — exception kills future executions. */
    @Scheduled(fixedDelay = 60000)
    fun generateDailyReport() {
        repository.computeStats() // may throw — not caught
    }

    /** Safe: @Scheduled with catch block protecting the full body. */
    @Scheduled(fixedDelay = 60000)
    fun generateDailyReportSafe() {
        try {
            repository.computeStats()
        } catch (e: Exception) {
            logger.error("Daily report generation failed", e)
        }
    }

    // ── BULK_OPERATION_IN_LOOP ────────────────────────────────────────────────

    /** repository.save() inside a for loop — N separate database round-trips. */
    fun saveAll(items: List<String>) {
        for (item in items) {
            repository.save(item) // one round-trip per item
        }
    }

    /** Safe: collect all items and call saveAll() once. */
    fun saveAllSafe(items: List<String>) {
        repository.saveAll(items)
    }

    // ── SPAN_NOT_CLOSED ───────────────────────────────────────────────────────

    /** Span started but end() not guarded by finally — leaks on exception paths. */
    fun tracedOperation(id: String) {
        val span = tracer.startSpan("tracedOperation")
        repository.save(id) // may throw — span leaks
        span.end() // only on happy path
    }

    /** Safe: span.end() in finally block. */
    fun tracedOperationSafe(id: String) {
        val span = tracer.startSpan("tracedOperation")
        try {
            repository.save(id)
        } finally {
            span.end()
        }
    }
}

// ── KAFKA_DEAD_LETTER_NOT_CONFIGURED ─────────────────────────────────────────

/** @KafkaListener without errorHandler or DLT recoverer — failed messages are silently discarded. */
@Service
class KotlinNewRulesKafkaListener(
    private val repository: KotlinRepository,
    private val logger: Logger
) {

    @KafkaListener(topics = ["payments"])
    fun onPayment(payload: String) {
        repository.save(payload)
        // No errorHandler, no DeadLetterPublishingRecoverer
    }

    /** Safe: errorHandler attribute set. */
    @KafkaListener(topics = ["refunds"], errorHandler = "myErrorHandler")
    fun onRefund(payload: String) {
        repository.save(payload)
    }
}

// ── OUTBOX_PATTERN_MISSING ────────────────────────────────────────────────────

/** DB write + Kafka send in the same method — no transactional outbox. */
@Service
class KotlinOrderService(
    private val repository: KotlinRepository,
    private val kafkaTemplate: KafkaTemplate
) {

    /** triggers OUTBOX_PATTERN_MISSING: save + send without publishEvent or outbox table. */
    fun placeOrder(orderId: String) {
        repository.save(orderId)
        kafkaTemplate.send("orders", orderId) // may lose if crash between commit and send
    }

    /** Safe: decouple the send from the commit via ApplicationEventPublisher. */
    fun placeOrderSafe(orderId: String, publisher: ApplicationEventPublisher) {
        repository.save(orderId)
        publisher.publishEvent(orderId) // relayed after commit in @TransactionalEventListener
    }
}

// ── SECRET_IN_STRING_LITERAL ──────────────────────────────────────────────────

/** Hardcoded credentials in source — triggers SECRET_IN_STRING_LITERAL. */
@Service
class KotlinSecretConfig(private val dataSourceBuilder: DataSourceBuilder) {

    /** triggers SECRET_IN_STRING_LITERAL: hardcoded password in a setter call. */
    fun configure() {
        dataSourceBuilder.setPassword("SuperSecr3t!") // literal password committed to source
    }

    /** Safe: uses a Spring property reference instead of a literal. */
    fun configureSafe() {
        dataSourceBuilder.setPassword("\${db.password}")
    }
}

// ── COROUTINE_EXCEPTION_NOT_HANDLED + BLOCKING_CALL_IN_COROUTINE ─────────────

/** GlobalScope.launch without a CoroutineExceptionHandler — crash is silently dropped. */
@Service
class KotlinCoroutineService(
    private val repository: KotlinRepository,
    private val logger: Logger
) {
    /** triggers COROUTINE_EXCEPTION_NOT_HANDLED (ERROR — GlobalScope). */
    fun processInBackground(id: String) {
        GlobalScope.launch {
            repository.save(id) // if this throws, the exception is silently lost
        }
    }

    /** triggers BLOCKING_CALL_IN_COROUTINE: Thread.sleep inside launch without withContext(IO). */
    fun waitInBackground(millis: Long) {
        GlobalScope.launch {
            Thread.sleep(millis) // blocks the dispatcher thread
        }
    }

    /** triggers BLOCKING_CALL_IN_COROUTINE: Thread.sleep in a suspend function. */
    suspend fun sleepInSuspend(millis: Long) {
        Thread.sleep(millis) // parks the dispatcher thread
    }

    /** Safe: a main-safe suspend function moves the blocking call to the IO pool. */
    suspend fun sleepInSuspendSafe(millis: Long) {
        withContext(Dispatchers.IO) { Thread.sleep(millis) }
    }

    /** Safe: a map lookup in a coroutine is not a blocking Future.get(). */
    fun lookupInBackground(cache: Map<String, String>, key: String) {
        GlobalScope.launch(CoroutineExceptionHandler { _, ex -> logger.error("Lookup failed", ex) }) {
            cache.get(key)
        }
    }

    /** Safe: CoroutineExceptionHandler provided in context. */
    fun processInBackgroundSafe(id: String) {
        GlobalScope.launch(CoroutineExceptionHandler { _, ex ->
            logger.error("Background processing failed for {}", id, ex)
        }) {
            repository.save(id)
        }
    }

    /** Safe: Thread.sleep wrapped in withContext(Dispatchers.IO). */
    fun waitInBackgroundSafe(millis: Long) {
        GlobalScope.launch(CoroutineExceptionHandler { _, ex ->
            logger.error("Wait failed", ex)
        }) {
            withContext(Dispatchers.IO) { Thread.sleep(millis) }
        }
    }
}

// ── FLOW_EXCEPTION_NOT_CAUGHT ─────────────────────────────────────────────────

/** Flow.collect without .catch — exceptions propagate uncaught to the coroutine. */
@Service
class KotlinFlowProcessor(
    private val repository: KotlinRepository,
    private val logger: Logger
) {
    /** triggers FLOW_EXCEPTION_NOT_CAUGHT: collect with no upstream .catch. */
    fun processItems(items: Flow<String>) {
        items.collect { item ->
            repository.save(item) // exception propagates uncaught to the coroutine
        }
    }

    /** Safe: .catch operator applied before .collect. */
    fun processItemsSafe(items: Flow<String>) {
        items.catch { ex ->
            logger.error("Flow error during processing", ex)
        }.collect { item ->
            repository.save(item)
        }
    }
}

/**
 * Controller that triggers every new rule on each request, making all fixture methods
 * reachable from a flow entrypoint.
 */
@RestController
class KotlinNewRulesController(
    private val fixture: KotlinNewRulesFixture,
    private val coroutineService: KotlinCoroutineService,
    private val flowProcessor: KotlinFlowProcessor
) {

    @GetMapping("/kotlin-new-rules")
    fun trigger(id: String, items: List<String>): String {
        fixture.waitForData()              // → INTERRUPTED_EXCEPTION_SWALLOWED
        fixture.processAsync(id)           // → COMPLETABLEFUTURE_EXCEPTION_NOT_HANDLED
        fixture.processInParallel(items)   // → EXECUTOR_NOT_SHUTDOWN
        fixture.notifyAsync(id)            // → ASYNC_ON_PRIVATE_METHOD (via wrapper)
        fixture.saveAll(items)             // → BULK_OPERATION_IN_LOOP
        fixture.tracedOperation(id)        // → SPAN_NOT_CLOSED
        coroutineService.processInBackground(id)  // → COROUTINE_EXCEPTION_NOT_HANDLED
        coroutineService.waitInBackground(100L)   // → BLOCKING_CALL_IN_COROUTINE
        return id
    }

    @GetMapping("/kotlin-flow")
    fun triggerFlow(items: Flow<String>): String {
        flowProcessor.processItems(items)  // → FLOW_EXCEPTION_NOT_CAUGHT
        return "done"
    }
}
