package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.InvocationEvidence;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single catalog of calls that block the calling thread.
 *
 * <p>Rules that care about blocking (reactive event loops, coroutine dispatchers, and future
 * additions) share this catalog instead of each keeping its own string heuristics. A call is
 * classified from the receiver <em>type</em> whenever the parsers could resolve it, which yields
 * {@link Confidence#HIGH}; only when the type is unknown does it fall back to a naming hint, which
 * yields {@link Confidence#MEDIUM}. Bounded variants ({@code Future.get(timeout, unit)},
 * {@code latch.await(timeout, unit)}) are deliberately not classified: they are covered by
 * dedicated timeout rules and are a judgement call rather than a defect.</p>
 */
public final class BlockingCalls {

    /** Coarse family; callers use it to pick a severity. */
    public enum Category {
        /** Parks the thread on a JVM primitive: sleep, wait, locks, latches, futures, reactive block. */
        THREAD_BLOCKING,
        /** Waits on JDBC, JPA, HTTP, file or socket I/O. */
        BLOCKING_IO
    }

    /**
     * A classified blocking call.
     *
     * @param kind        stable fine-grained label such as {@code sleep}, {@code future} or {@code jdbc}
     * @param description human readable call, for example {@code CountDownLatch.await()}
     */
    public record BlockingCall(Category category, String kind, String description, Confidence confidence) {
    }

    private record Entry(
            Set<String> types, Set<String> methods, int minArgs, int maxArgs,
            Category category, String kind) {
        boolean matches(String type, String method, int argc) {
            return types.contains(type) && methods.contains(method) && argc >= minArgs && argc <= maxArgs;
        }
    }

    private static final int ANY = Integer.MAX_VALUE;

    private static final Set<String> BLOCKING_QUEUES = Set.of(
            "BlockingQueue", "LinkedBlockingQueue", "ArrayBlockingQueue", "PriorityBlockingQueue",
            "SynchronousQueue", "DelayQueue", "LinkedTransferQueue", "TransferQueue",
            "BlockingDeque", "LinkedBlockingDeque");
    private static final Set<String> FUTURES = Set.of(
            "Future", "CompletableFuture", "FutureTask", "ForkJoinTask", "ScheduledFuture", "RunnableFuture",
            "RecursiveTask", "RecursiveAction");
    private static final Set<String> REACTOR_TYPES = Set.of("Mono", "Flux", "ParallelFlux");
    private static final Set<String> RX_TYPES = Set.of("Single", "Observable", "Flowable", "Maybe", "Completable");
    private static final Set<String> RX_BLOCKING_METHODS = Set.of(
            "blockingGet", "blockingFirst", "blockingLast", "blockingSingle", "blockingAwait",
            "blockingSubscribe", "blockingIterable", "blockingForEach", "blockingMostRecent",
            "blockingLatest", "blockingNext");

    private static final List<Entry> TYPED = List.of(
            // ── thread-blocking primitives ──────────────────────────────────────────────────────
            entry(Set.of("Thread", "TimeUnit"), Set.of("sleep"), 0, ANY, Category.THREAD_BLOCKING, "sleep"),
            entry(Set.of("Object"), Set.of("wait"), 0, 2, Category.THREAD_BLOCKING, "monitor-wait"),
            entry(Set.of("LockSupport"), Set.of("park", "parkNanos", "parkUntil"), 0, ANY,
                    Category.THREAD_BLOCKING, "park"),
            entry(Set.of("CountDownLatch", "CyclicBarrier"), Set.of("await"), 0, 0,
                    Category.THREAD_BLOCKING, "synchronizer"),
            entry(Set.of("Condition"), Set.of("await", "awaitUninterruptibly"), 0, 0,
                    Category.THREAD_BLOCKING, "synchronizer"),
            entry(Set.of("Semaphore"), Set.of("acquire", "acquireUninterruptibly"), 0, 1,
                    Category.THREAD_BLOCKING, "synchronizer"),
            entry(Set.of("Phaser"), Set.of("awaitAdvance", "arriveAndAwaitAdvance", "awaitAdvanceInterruptibly"),
                    0, 1, Category.THREAD_BLOCKING, "synchronizer"),
            entry(Set.of("Exchanger"), Set.of("exchange"), 1, 1, Category.THREAD_BLOCKING, "synchronizer"),
            entry(BLOCKING_QUEUES, Set.of("take"), 0, 0, Category.THREAD_BLOCKING, "queue"),
            entry(BLOCKING_QUEUES, Set.of("put", "transfer"), 1, 1, Category.THREAD_BLOCKING, "queue"),
            entry(FUTURES, Set.of("get", "join"), 0, 0, Category.THREAD_BLOCKING, "future"),
            entry(Set.of("CompletionService", "ExecutorCompletionService"), Set.of("take"), 0, 0,
                    Category.THREAD_BLOCKING, "future"),
            entry(Set.of("ExecutorService", "ThreadPoolExecutor", "ForkJoinPool", "ScheduledExecutorService"),
                    Set.of("invokeAll", "invokeAny"), 1, 1, Category.THREAD_BLOCKING, "future"),
            entry(Set.of("Thread"), Set.of("join"), 0, 0, Category.THREAD_BLOCKING, "thread-join"),
            entry(Set.of("Process"), Set.of("waitFor"), 0, 0, Category.THREAD_BLOCKING, "process"),
            entry(REACTOR_TYPES, Set.of("block", "blockFirst", "blockLast", "blockOptional"), 0, ANY,
                    Category.THREAD_BLOCKING, "reactive-block"),
            entry(RX_TYPES, RX_BLOCKING_METHODS, 0, ANY, Category.THREAD_BLOCKING, "reactive-block"),

            // ── blocking I/O ────────────────────────────────────────────────────────────────────
            entry(Set.of("Connection", "Statement", "PreparedStatement", "CallableStatement"),
                    Set.of("execute", "executeQuery", "executeUpdate", "executeBatch", "executeLargeUpdate",
                            "commit", "rollback"),
                    0, ANY, Category.BLOCKING_IO, "jdbc"),
            entry(Set.of("ResultSet"), Set.of("next"), 0, 0, Category.BLOCKING_IO, "jdbc"),
            entry(Set.of("DataSource", "DriverManager"), Set.of("getConnection"), 0, ANY,
                    Category.BLOCKING_IO, "jdbc"),
            entry(Set.of("JdbcTemplate", "NamedParameterJdbcTemplate", "JdbcOperations", "JdbcClient",
                            "SimpleJdbcInsert"),
                    Set.of("query", "queryForObject", "queryForList", "queryForMap", "queryForRowSet",
                            "queryForStream", "update", "batchUpdate", "execute", "call", "executeAndReturnKey"),
                    0, ANY, Category.BLOCKING_IO, "jdbc"),
            entry(Set.of("EntityManager"),
                    Set.of("find", "persist", "merge", "remove", "flush", "refresh", "getReference"),
                    0, ANY, Category.BLOCKING_IO, "jpa"),
            entry(Set.of("Query", "TypedQuery"), Set.of("getResultList", "getSingleResult", "executeUpdate"),
                    0, 0, Category.BLOCKING_IO, "jpa"),
            entry(Set.of("RestTemplate"),
                    Set.of("getForObject", "getForEntity", "postForObject", "postForEntity", "postForLocation",
                            "put", "delete", "exchange", "execute", "headForHeaders", "patchForObject",
                            "optionsForAllow"),
                    0, ANY, Category.BLOCKING_IO, "http"),
            entry(Set.of("HttpURLConnection", "URLConnection"),
                    Set.of("getInputStream", "getResponseCode", "connect", "getOutputStream"),
                    0, 0, Category.BLOCKING_IO, "http"),
            entry(Set.of("URL"), Set.of("openStream"), 0, 0, Category.BLOCKING_IO, "http"),
            entry(Set.of("HttpClient"), Set.of("send"), 1, 2, Category.BLOCKING_IO, "http"),
            entry(Set.of("HttpClient", "CloseableHttpClient"), Set.of("execute"), 1, ANY,
                    Category.BLOCKING_IO, "http"),
            entry(Set.of("ServerSocket"), Set.of("accept"), 0, 0, Category.BLOCKING_IO, "socket"),
            entry(Set.of("Files"),
                    Set.of("readAllBytes", "readString", "readAllLines", "write", "writeString", "lines", "list",
                            "walk", "find", "copy", "move", "delete", "newBufferedReader", "newBufferedWriter",
                            "newInputStream", "newOutputStream"),
                    0, ANY, Category.BLOCKING_IO, "file-io"),
            entry(Set.of("File", "Path"),
                    Set.of("readText", "readBytes", "readLines", "writeText", "writeBytes", "appendText",
                            "forEachLine", "useLines"),
                    0, ANY, Category.BLOCKING_IO, "file-io"),
            entry(Set.of("InputStream", "FileInputStream", "BufferedInputStream", "DataInputStream",
                            "ObjectInputStream", "Reader", "BufferedReader", "InputStreamReader", "FileReader"),
                    Set.of("read", "readLine", "readAllBytes", "readNBytes", "readObject", "transferTo"),
                    0, ANY, Category.BLOCKING_IO, "stream-io"));

    /** Type names whose constructors open a file or socket, which blocks on the file system or network. */
    private static final Set<String> BLOCKING_CONSTRUCTORS = Set.of(
            "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "RandomAccessFile", "Socket");

    /** {@code uni.await().indefinitely()}, {@code uni.await().asOptional().atMost(d)}. */
    private static final Pattern MUTINY_AWAIT_SCOPE = Pattern.compile(
            "(?s).*\\bawait\\s*\\(\\s*\\)(\\s*\\.\\s*asOptional\\s*\\(\\s*\\))?\\s*");
    private static final Set<String> MUTINY_AWAIT_TERMINALS = Set.of("indefinitely", "atMost");

    private BlockingCalls() {
    }

    /** Classifies an invocation, or returns empty when it is not provably or plausibly blocking. */
    public static Optional<BlockingCall> classify(InvocationEvidence invocation) {
        String method = invocation.methodName();
        int argc = invocation.arguments().size();
        String scope = invocation.scope().strip();

        if (MUTINY_AWAIT_TERMINALS.contains(method) && MUTINY_AWAIT_SCOPE.matcher(scope).matches()) {
            return Optional.of(new BlockingCall(Category.THREAD_BLOCKING, "mutiny-await",
                    "Uni/Multi.await()." + method + "()", Confidence.HIGH));
        }
        String type = knownType(invocation);
        if (RX_BLOCKING_METHODS.contains(method) && type.isEmpty()) {
            // The blocking* names are specific to RxJava, so an unresolved receiver is still a good signal.
            return Optional.of(new BlockingCall(Category.THREAD_BLOCKING, "reactive-block",
                    "RxJava " + method + "()", Confidence.MEDIUM));
        }

        if (!type.isEmpty()) {
            for (Entry entry : TYPED) {
                if (entry.matches(type, method, argc)) {
                    return Optional.of(new BlockingCall(entry.category(), entry.kind(),
                            type + "." + method + "()", Confidence.HIGH));
                }
            }
            return Optional.empty();
        }

        if (scope.isEmpty() && BLOCKING_CONSTRUCTORS.contains(method)) {
            return Optional.of(new BlockingCall(Category.BLOCKING_IO, "file-io", "new " + method + "()",
                    Confidence.MEDIUM));
        }
        if (scope.isEmpty() || scope.equals("this")) {
            if (method.equals("wait") && argc <= 2) {
                return Optional.of(new BlockingCall(Category.THREAD_BLOCKING, "monitor-wait", "Object.wait()",
                        Confidence.MEDIUM));
            }
            return Optional.empty();
        }
        return hinted(scope, method, argc);
    }

    /** Naming-hint fallback for receivers whose type could not be resolved. Always MEDIUM. */
    private static Optional<BlockingCall> hinted(String scope, String method, int argc) {
        String hint = scope.toLowerCase(Locale.ROOT);
        boolean plainName = hint.indexOf('(') < 0;

        if (method.equals("wait") && argc <= 2 && plainName) {
            return medium(Category.THREAD_BLOCKING, "monitor-wait", "Object.wait()");
        }
        if (method.equals("await") && argc == 0 && plainName
                && (hint.contains("latch") || hint.contains("barrier"))) {
            return medium(Category.THREAD_BLOCKING, "synchronizer", "latch/barrier.await()");
        }
        if ((method.equals("acquire") || method.equals("acquireUninterruptibly"))
                && plainName && hint.contains("semaphore")) {
            return medium(Category.THREAD_BLOCKING, "synchronizer", "Semaphore.acquire()");
        }
        if ((method.equals("get") || method.equals("join")) && argc == 0
                && (hint.contains("future") || hint.contains("promise"))) {
            return medium(Category.THREAD_BLOCKING, "future", "Future." + method + "()");
        }
        if (Set.of("block", "blockFirst", "blockLast", "blockOptional").contains(method)
                && (hint.contains("mono") || hint.contains("flux"))) {
            return medium(Category.THREAD_BLOCKING, "reactive-block", "Mono/Flux." + method + "()");
        }
        if (((method.equals("take") && argc == 0) || (method.equals("put") && argc == 1))
                && plainName && hint.contains("blockingqueue")) {
            return medium(Category.THREAD_BLOCKING, "queue", "BlockingQueue." + method + "()");
        }
        return Optional.empty();
    }

    private static Optional<BlockingCall> medium(Category category, String kind, String description) {
        return Optional.of(new BlockingCall(category, kind, description, Confidence.MEDIUM));
    }

    /**
     * The simple receiver type name when it is known: from the resolved {@code receiverType}, or from
     * a scope that is a plain type reference such as {@code Thread} or {@code java.lang.Thread}.
     * Call chains ({@code foo().bar()}) never yield a type.
     */
    private static String knownType(InvocationEvidence invocation) {
        String fromReceiver = ExecutionContexts.simpleTypeName(invocation.receiverType());
        if (!fromReceiver.isEmpty() && Character.isUpperCase(fromReceiver.charAt(0))) return fromReceiver;

        String scope = invocation.scope().strip();
        if (scope.isEmpty() || scope.indexOf('(') >= 0) return "";
        for (String segment : scope.split("\\.")) {
            if (!segment.isEmpty() && Character.isUpperCase(segment.charAt(0))) return segment;
        }
        return "";
    }

    private static Entry entry(
            Set<String> types, Set<String> methods, int minArgs, int maxArgs, Category category, String kind) {
        return new Entry(types, methods, minArgs, maxArgs, category, kind);
    }
}
