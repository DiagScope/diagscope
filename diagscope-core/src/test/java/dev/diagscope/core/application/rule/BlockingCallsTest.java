package dev.diagscope.core.application.rule;

import dev.diagscope.core.application.rule.BlockingCalls.Category;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class BlockingCallsTest {

    // ── thread-blocking primitives ────────────────────────────────────────────

    @Test
    void thread_sleep_is_recognised_from_a_static_scope_with_and_without_package() {
        assertBlocking(call("Thread", "", "sleep", 1), Category.THREAD_BLOCKING, "sleep", Confidence.HIGH);
        assertBlocking(call("java.lang.Thread", "", "sleep", 1), Category.THREAD_BLOCKING, "sleep", Confidence.HIGH);
        assertBlocking(call("TimeUnit.SECONDS", "", "sleep", 1), Category.THREAD_BLOCKING, "sleep", Confidence.HIGH);
    }

    @Test
    void latch_await_uses_the_resolved_type_and_ignores_the_bounded_overload() {
        assertBlocking(call("latch", "java.util.concurrent.CountDownLatch", "await", 0),
                Category.THREAD_BLOCKING, "synchronizer", Confidence.HIGH);
        assertThat(BlockingCalls.classify(call("latch", "CountDownLatch", "await", 2))).isEmpty();
    }

    @Test
    void latch_await_falls_back_to_a_name_hint_with_medium_confidence() {
        assertBlocking(call("startupLatch", "", "await", 0),
                Category.THREAD_BLOCKING, "synchronizer", Confidence.MEDIUM);
    }

    @Test
    void future_get_requires_a_future_type_or_a_future_named_receiver() {
        assertBlocking(call("pending", "CompletableFuture<String>", "get", 0),
                Category.THREAD_BLOCKING, "future", Confidence.HIGH);
        assertBlocking(call("pending", "CompletableFuture<String>", "join", 0),
                Category.THREAD_BLOCKING, "future", Confidence.HIGH);
        assertBlocking(call("orderFuture", "", "get", 0), Category.THREAD_BLOCKING, "future", Confidence.MEDIUM);
        assertThat(BlockingCalls.classify(call("pending", "CompletableFuture<String>", "get", 2))).isEmpty();
    }

    @Test
    void collection_and_optional_get_are_not_blocking() {
        assertThat(BlockingCalls.classify(call("map", "Map<String, String>", "get", 1))).isEmpty();
        assertThat(BlockingCalls.classify(call("list", "List<String>", "get", 1))).isEmpty();
        assertThat(BlockingCalls.classify(call("optional", "Optional<String>", "get", 0))).isEmpty();
        assertThat(BlockingCalls.classify(call("optional", "", "get", 0))).isEmpty();
        // A known non-future type wins over a misleading variable name.
        assertThat(BlockingCalls.classify(call("futuresById", "Map<String, Order>", "get", 0))).isEmpty();
    }

    @Test
    void object_wait_is_recognised_with_and_without_a_receiver() {
        assertBlocking(call("lock", "Object", "wait", 0), Category.THREAD_BLOCKING, "monitor-wait", Confidence.HIGH);
        assertBlocking(call("", "", "wait", 0), Category.THREAD_BLOCKING, "monitor-wait", Confidence.MEDIUM);
        assertBlocking(call("lock", "", "wait", 1), Category.THREAD_BLOCKING, "monitor-wait", Confidence.MEDIUM);
    }

    @Test
    void blocking_queue_take_requires_a_blocking_queue_type() {
        assertBlocking(call("queue", "BlockingQueue<Task>", "take", 0),
                Category.THREAD_BLOCKING, "queue", Confidence.HIGH);
        assertBlocking(call("queue", "LinkedBlockingQueue<Task>", "put", 1),
                Category.THREAD_BLOCKING, "queue", Confidence.HIGH);
        assertThat(BlockingCalls.classify(call("queue", "Queue<Task>", "take", 0))).isEmpty();
        assertThat(BlockingCalls.classify(call("cache", "Map<String, Task>", "put", 2))).isEmpty();
    }

    @Test
    void thread_join_is_blocking_but_string_join_is_not() {
        assertBlocking(call("worker", "Thread", "join", 0), Category.THREAD_BLOCKING, "thread-join", Confidence.HIGH);
        assertThat(BlockingCalls.classify(call("String", "", "join", 2))).isEmpty();
    }

    // ── reactive types ────────────────────────────────────────────────────────

    @Test
    void reactor_block_is_recognised_by_type_or_by_a_mono_flux_hint() {
        assertBlocking(call("mono", "Mono<Order>", "block", 0),
                Category.THREAD_BLOCKING, "reactive-block", Confidence.HIGH);
        assertBlocking(call("flux", "Flux<Order>", "blockLast", 1),
                Category.THREAD_BLOCKING, "reactive-block", Confidence.HIGH);
        assertBlocking(call("webClient.get().retrieve().bodyToMono(Order.class)", "", "block", 0),
                Category.THREAD_BLOCKING, "reactive-block", Confidence.MEDIUM);
        assertThat(BlockingCalls.classify(call("service", "OrderService", "block", 0))).isEmpty();
    }

    @Test
    void mutiny_await_terminals_are_recognised_only_after_await() {
        assertBlocking(call("uni.await()", "", "indefinitely", 0),
                Category.THREAD_BLOCKING, "mutiny-await", Confidence.HIGH);
        assertBlocking(call("repository.load(id)\n    .await()", "", "atMost", 1),
                Category.THREAD_BLOCKING, "mutiny-await", Confidence.HIGH);
        assertBlocking(call("uni.await().asOptional()", "", "indefinitely", 0),
                Category.THREAD_BLOCKING, "mutiny-await", Confidence.HIGH);
        assertThat(BlockingCalls.classify(call("uni", "", "indefinitely", 0))).isEmpty();
        assertThat(BlockingCalls.classify(call("deferred", "", "await", 0))).isEmpty();
    }

    @Test
    void rxjava_blocking_operators_are_recognised_even_without_a_resolved_type() {
        assertBlocking(call("single", "Single<Order>", "blockingGet", 0),
                Category.THREAD_BLOCKING, "reactive-block", Confidence.HIGH);
        assertBlocking(call("single", "", "blockingGet", 0),
                Category.THREAD_BLOCKING, "reactive-block", Confidence.MEDIUM);
        assertThat(BlockingCalls.classify(call("single", "OrderService", "blockingGet", 0))).isEmpty();
    }

    // ── blocking I/O ──────────────────────────────────────────────────────────

    @Test
    void jdbc_jpa_and_http_clients_are_blocking_io() {
        assertBlocking(call("statement", "PreparedStatement", "executeQuery", 0),
                Category.BLOCKING_IO, "jdbc", Confidence.HIGH);
        assertBlocking(call("jdbc", "JdbcTemplate", "queryForObject", 3),
                Category.BLOCKING_IO, "jdbc", Confidence.HIGH);
        assertBlocking(call("em", "EntityManager", "find", 2), Category.BLOCKING_IO, "jpa", Confidence.HIGH);
        assertBlocking(call("rest", "RestTemplate", "getForObject", 2), Category.BLOCKING_IO, "http", Confidence.HIGH);
        assertBlocking(call("client", "java.net.http.HttpClient", "send", 2),
                Category.BLOCKING_IO, "http", Confidence.HIGH);
        assertThat(BlockingCalls.classify(call("client", "HttpClient", "sendAsync", 2))).isEmpty();
    }

    @Test
    void file_access_is_blocking_io_but_in_memory_streams_are_not() {
        assertBlocking(call("Files", "", "readAllBytes", 1), Category.BLOCKING_IO, "file-io", Confidence.HIGH);
        assertBlocking(call("", "", "FileInputStream", 1), Category.BLOCKING_IO, "file-io", Confidence.MEDIUM);
        assertThat(BlockingCalls.classify(call("buffer", "ByteArrayInputStream", "read", 0))).isEmpty();
    }

    @Test
    void unrelated_calls_are_not_blocking() {
        assertThat(BlockingCalls.classify(call("repository", "OrderRepository", "save", 1))).isEmpty();
        assertThat(BlockingCalls.classify(call("", "", "process", 1))).isEmpty();
        assertThat(BlockingCalls.classify(call("logger", "Logger", "info", 1))).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void assertBlocking(
            InvocationEvidence invocation, Category category, String kind, Confidence confidence) {
        assertThat(BlockingCalls.classify(invocation))
                .as("%s.%s", invocation.scope(), invocation.methodName())
                .hasValueSatisfying(call -> {
                    assertThat(call.category()).isEqualTo(category);
                    assertThat(call.kind()).isEqualTo(kind);
                    assertThat(call.confidence()).isEqualTo(confidence);
                });
    }

    private static InvocationEvidence call(String scope, String receiverType, String method, int argumentCount) {
        return new InvocationEvidence(
                new SourceLocation(Path.of("src/main/java/example/Service.java"), 10, 10),
                scope, receiverType, method, Collections.nCopies(argumentCount, "arg"),
                InvocationResultUsage.UNKNOWN);
    }
}
