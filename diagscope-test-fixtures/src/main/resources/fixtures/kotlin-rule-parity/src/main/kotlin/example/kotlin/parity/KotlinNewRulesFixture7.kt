package example.kotlin.parity

import java.util.concurrent.CompletableFuture
import java.util.regex.Pattern

/**
 * Fixture: covers REGEX_COMPILED_IN_LOOP, STRING_FORMAT_IN_LOOP,
 * TRANSACTIONAL_ASYNC_COMBINATION, THREAD_SLEEP_IN_FLOW, SEQUENTIAL_FUTURE_JOIN_IN_LOOP.
 */

// ── REGEX_COMPILED_IN_LOOP ────────────────────────────────────────────────────

@RestController
open class KotlinWave8RegexService {

    /** REGEX_COMPILED_IN_LOOP: Pattern.compile() inside a for-each loop. */
    @GetMapping("/wave8/validate")
    open fun validateAll(inputs: List<String>): List<String> {
        val result = mutableListOf<String>()
        for (input in inputs) {
            val p = Pattern.compile("\\d{3}-\\d{4}") // → REGEX_COMPILED_IN_LOOP
            if (p.matcher(input).matches()) result.add(input)
        }
        return result
    }

    /** Safe: pattern compiled once as a companion object constant. */
    companion object {
        private val PHONE_PATTERN = Pattern.compile("\\d{3}-\\d{4}")
    }

    @GetMapping("/wave8/validate/safe")
    open fun validateAllSafe(inputs: List<String>): List<String> =
        inputs.filter { PHONE_PATTERN.matcher(it).matches() }
}

// ── STRING_FORMAT_IN_LOOP ─────────────────────────────────────────────────────

@RestController
open class KotlinWave8FormattingService {

    /** STRING_FORMAT_IN_LOOP: String.format() inside a loop — re-parses format on each call. */
    @GetMapping("/wave8/labels")
    open fun buildLabels(items: List<String>): List<String> {
        val labels = mutableListOf<String>()
        for (item in items) {
            labels.add(String.format("Item: %s [processed]", item)) // → STRING_FORMAT_IN_LOOP
        }
        return labels
    }

    /** Safe: string template avoids re-parsing the format on every iteration. */
    @GetMapping("/wave8/labels/safe")
    open fun buildLabelsSafe(items: List<String>): List<String> =
        items.map { "Item: $it [processed]" }
}

// ── TRANSACTIONAL_ASYNC_COMBINATION ──────────────────────────────────────────

@Service
open class KotlinWave8NotificationService {

    /** TRANSACTIONAL_ASYNC_COMBINATION: @Transactional + @Async on same method. */
    @Transactional
    @Async
    open fun sendNotification(userId: String) { // → TRANSACTIONAL_ASYNC_COMBINATION
        // transaction context is absent on the async thread
    }

    /** Safe: @Async only on wrapper, @Transactional only on sync helper. */
    @Async
    open fun sendNotificationSafe(userId: String) {
        doSendTransactional(userId)
    }

    @Transactional
    open fun doSendTransactional(userId: String) { /* safe */ }
}

// ── THREAD_SLEEP_IN_FLOW ──────────────────────────────────────────────────────

@RestController
open class KotlinWave8RetryController {

    /** THREAD_SLEEP_IN_FLOW: Thread.sleep() inside a REST handler. */
    @PostMapping("/wave8/retry")
    open fun retryOperation(payload: String) {
        Thread.sleep(2000) // → THREAD_SLEEP_IN_FLOW — blocks server thread
    }

    /** Safe: use coroutine delay or Spring Retry with back-off. */
    @PostMapping("/wave8/retry/safe")
    open fun retryOperationSafe(payload: String) {
        // use @Retryable + @Backoff on the method
    }
}

// ── SEQUENTIAL_FUTURE_JOIN_IN_LOOP ────────────────────────────────────────────

@RestController
open class KotlinWave8FutureService {

    /** SEQUENTIAL_FUTURE_JOIN_IN_LOOP: join() inside a loop — serialises parallel tasks. */
    @GetMapping("/wave8/futures")
    open fun fetchAll(ids: List<String>): List<String> {
        val futures = ids.map { id -> CompletableFuture.supplyAsync { "result-$id" } }
        val results = mutableListOf<String>()
        for (future in futures) {
            results.add(future.join()) // → SEQUENTIAL_FUTURE_JOIN_IN_LOOP
        }
        return results
    }

    /** Safe: allOf() then getNow() preserves true parallelism. */
    @GetMapping("/wave8/futures/safe")
    open fun fetchAllSafe(ids: List<String>): List<String> {
        val futures = ids.map { id -> CompletableFuture.supplyAsync { "result-$id" } }
        CompletableFuture.allOf(*futures.toTypedArray()).join()
        return futures.map { it.getNow("") }
    }
}
