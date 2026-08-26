package example.java.parity;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

@interface RestController {}
@interface GetMapping { String value(); }
@interface Scheduled { String cron() default ""; long fixedDelay() default -1L; }
@interface Retryable {}
@interface Recover {}
@interface Timed { String value() default ""; }
@interface Service {}
@interface Repository {}
@interface KafkaListener { String[] topics(); String errorHandler() default ""; }
@interface KafkaHandler {}
@interface Transactional { Propagation propagation() default Propagation.REQUIRED; }

enum Propagation { REQUIRED, REQUIRES_NEW, MANDATORY }

interface Logger {
    void info(String message, Object... arguments);
    void warn(String message, Object... arguments);
    void error(String message, Object... arguments);
}

interface Completion {
    Completion whenComplete(BiConsumer<Object, Throwable> callback);
}

interface ExecutorService {
    Completion submit(Runnable task);
    void shutdown();
    void shutdownNow();
}
interface KafkaTemplate { Completion send(String topic, String payload); }
interface Acknowledgment { void acknowledge(); }

interface RemoteResponse {
    RemoteResponse onErrorReturn(String value);
    RemoteResponse onErrorResume(Function<Throwable, String> handler);
}

interface RemoteClient {
    void refresh();
    RemoteResponse remote();
    String describeFailure(Throwable failure);
}

interface DataSource { Connection getConnection(); }
interface Connection extends AutoCloseable {
    PreparedStatement prepareStatement(String sql);
    void close();
}
interface PreparedStatement extends AutoCloseable {
    ResultSet executeQuery();
    void close();
}
interface ResultSet extends AutoCloseable { void close(); }
interface EntityManagerFactory { EntityManager createEntityManager(); }
interface EntityManager extends AutoCloseable {
    Object find(Object type, String id);
    void close();
}
interface JdbcTemplate { DataSource getDataSource(); }

final class MDC {
    static void put(String key, String value) {}
    static void remove(String key) {}
    static void clear() {}
    static Map<String, String> getCopyOfContextMap() { return Map.of(); }
    static void setContextMap(Map<String, String> context) {}
}

@interface Async {}
@interface Incoming { String value(); }
@interface NonBlocking {}

// --- Tracing stubs (for SPAN_NOT_CLOSED) ---
interface Span { void end(); }
interface Tracer { Span startSpan(String name); }

// --- Executor stubs (for EXECUTOR_NOT_SHUTDOWN) ---
final class Executors {
    static ExecutorService newFixedThreadPool(int nThreads) { return null; }
    static ExecutorService newCachedThreadPool() { return null; }
    static ExecutorService newSingleThreadExecutor() { return null; }
}

interface Lock {
    void lock();
    void unlock();
    boolean tryLock();
}

interface CompletableFuture<T> {
    T get() throws Exception;
    T get(long timeout, java.util.concurrent.TimeUnit unit) throws Exception;
    T join();
    T getNow(T valueIfAbsent);
}

class ThreadLocal<T> {
    void set(T value) {}
    T get() { return null; }
    void remove() {}
}

interface Uni<T> {
    Uni<T> onFailure();
    Uni<T> recoverWithItem(T item);
    Uni<T> recoverWithItem(Function<Throwable, T> recovery);
    Subscription<T> subscribe();
}

interface Subscription<T> {
    void with(java.util.function.Consumer<T> item);
    void with(java.util.function.Consumer<T> item, java.util.function.Consumer<Throwable> failure);
}

// --- HTTP / reactive stubs (for HTTP_TIMEOUT_NOT_SET) ---
interface Mono<T> {
    T block();
    Mono<T> timeout(Object duration);
    Mono<T> map(Function<T, T> mapper);
}

interface WebClient {
    Mono<String> get(String uri);
}

// --- JPA stubs (for MISSING_TRANSACTION_ANNOTATION) ---
interface UserRepository {
    Object save(Object entity);
    void delete(Object entity);
    void deleteById(String id);
}

// --- Application event stubs (for OUTBOX_PATTERN_MISSING / suppression) ---
interface ApplicationEventPublisher { void publishEvent(Object event); }

// --- Security config stubs (for SECRET_IN_STRING_LITERAL) ---
interface DataSourceBuilder {
    DataSourceBuilder url(String url);
    DataSourceBuilder setPassword(String password);
}
