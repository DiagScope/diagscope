package example.java.parity;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Fixture: covers REGEX_COMPILED_IN_LOOP, STRING_FORMAT_IN_LOOP,
 * TRANSACTIONAL_ASYNC_COMBINATION, THREAD_SLEEP_IN_FLOW, SEQUENTIAL_FUTURE_JOIN_IN_LOOP.
 */

// ── REGEX_COMPILED_IN_LOOP ────────────────────────────────────────────────────

@RestController
class JavaWave8RegexService {

    /** REGEX_COMPILED_IN_LOOP: Pattern.compile() inside a for-each loop. */
    @GetMapping("/wave8/validate")
    List<String> validateAll(List<String> inputs) {
        var result = new java.util.ArrayList<String>();
        for (String input : inputs) {
            Pattern p = Pattern.compile("\\d{3}-\\d{4}"); // → REGEX_COMPILED_IN_LOOP
            if (p.matcher(input).matches()) result.add(input);
        }
        return result;
    }

    /** Safe: pattern compiled once as a constant and reused across iterations. */
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\d{3}-\\d{4}");

    @GetMapping("/wave8/validate/safe")
    List<String> validateAllSafe(List<String> inputs) {
        var result = new java.util.ArrayList<String>();
        for (String input : inputs) {
            if (PHONE_PATTERN.matcher(input).matches()) result.add(input);
        }
        return result;
    }
}

// ── STRING_FORMAT_IN_LOOP ─────────────────────────────────────────────────────

@RestController
class JavaWave8FormattingService {

    /** STRING_FORMAT_IN_LOOP: String.format() inside a loop — re-parses format on each call. */
    @GetMapping("/wave8/labels")
    List<String> buildLabels(List<String> items) {
        var labels = new java.util.ArrayList<String>();
        for (String item : items) {
            labels.add(String.format("Item: %s [processed]", item)); // → STRING_FORMAT_IN_LOOP
        }
        return labels;
    }

    /** Safe: StringBuilder avoids re-parsing the format on every iteration. */
    @GetMapping("/wave8/labels/safe")
    List<String> buildLabelsSafe(List<String> items) {
        var labels = new java.util.ArrayList<String>();
        for (String item : items) {
            labels.add("Item: " + item + " [processed]");
        }
        return labels;
    }
}

// ── TRANSACTIONAL_ASYNC_COMBINATION ──────────────────────────────────────────

@Service
class JavaWave8NotificationService {

    /** TRANSACTIONAL_ASYNC_COMBINATION: @Transactional + @Async on same method. */
    @Transactional
    @Async
    void sendNotification(String userId) { // → TRANSACTIONAL_ASYNC_COMBINATION
        // transaction context is absent on the async thread
    }

    /** Safe: @Transactional is on the sync helper, @Async only on the wrapper. */
    @Async
    void sendNotificationSafe(String userId) {
        doSendTransactional(userId); // delegates to a @Transactional sync method
    }

    @Transactional
    void doSendTransactional(String userId) { /* safe — transactional on sync method */ }
}

// ── THREAD_SLEEP_IN_FLOW ──────────────────────────────────────────────────────

@RestController
class JavaWave8RetryController {

    /** THREAD_SLEEP_IN_FLOW: Thread.sleep() inside a REST handler. */
    @PostMapping("/wave8/retry")
    void retryOperation(String payload) throws InterruptedException {
        Thread.sleep(2000); // → THREAD_SLEEP_IN_FLOW — blocks server thread
    }

    /** Safe: @Retryable with @Backoff delegates back-off to Spring Retry. */
    @PostMapping("/wave8/retry/safe")
    void retryOperationSafe(String payload) {
        // use @Retryable on the method or a separate service
    }
}

// ── SEQUENTIAL_FUTURE_JOIN_IN_LOOP ────────────────────────────────────────────

@RestController
class JavaWave8FutureService {

    /** SEQUENTIAL_FUTURE_JOIN_IN_LOOP: join() inside a loop — serialises parallel tasks. */
    @GetMapping("/wave8/futures")
    List<String> fetchAll(List<String> ids) {
        var futures = ids.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> "result-" + id))
                .toList();
        var results = new java.util.ArrayList<String>();
        for (CompletableFuture<String> future : futures) {
            results.add(future.join()); // → SEQUENTIAL_FUTURE_JOIN_IN_LOOP
        }
        return results;
    }

    /** Safe: collect all results after a single allOf(), preserving true parallelism. */
    @GetMapping("/wave8/futures/safe")
    List<String> fetchAllSafe(List<String> ids) {
        var futures = ids.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> "result-" + id))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream().map(f -> f.getNow("")).toList();
    }
}
