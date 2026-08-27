package example.java.parity;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

@interface RestController {}
@interface GetMapping { String value(); }
@interface Scheduled { String cron() default ""; long fixedDelay() default -1L; long fixedRate() default -1L; long initialDelay() default -1L; String initialDelayString() default ""; }
@interface Autowired {}
@interface Retryable { Class<?>[] include() default {}; Class<?>[] value() default {}; }
@interface Recover {}
@interface Timed { String value() default ""; }
@interface Service {}
@interface Repository {}
@interface KafkaListener { String[] topics(); String errorHandler() default ""; }
@interface CrossOrigin { String value() default ""; String[] origins() default {}; }
@interface Value { String value(); }
@interface PostMapping { String value() default ""; }
@interface PutMapping { String value() default ""; }
@interface KafkaHandler {}
@interface Transactional { Propagation propagation() default Propagation.REQUIRED; boolean readOnly() default false; }

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

class RestTemplate {
    String getForObject(String url) { return null; }
    String postForObject(String url, Object body) { return null; }
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

// --- Stubs for TRANSACTIONAL_ON_INTERFACE, MISSING_PAGINATION, KAFKA_RETRY_WITHOUT_BACKOFF ---

// Pageable stub (for MISSING_PAGINATION suppression)
interface Pageable {}

// OrderRepository for MISSING_PAGINATION fixture
interface OrderRepository {
    List<String> findAll();                        // triggers MISSING_PAGINATION
    List<String> findAll(Pageable pageable);       // suppressed — has Pageable
    List<String> findByStatus(String status);      // triggers MISSING_PAGINATION
    String findById(String id);                    // suppressed — not a collection
    long countByStatus(String status);             // suppressed — has "count"
}

// FixedBackOff stub (for KAFKA_RETRY_WITHOUT_BACKOFF)
class FixedBackOff {
    FixedBackOff() {}
    FixedBackOff(long interval, long maxAttempts) {}
}

class ExponentialBackOff {
    ExponentialBackOff(long initialInterval, double multiplier) {}
    void setMaxInterval(long maxInterval) {}
}

// --- Stubs for MASS_ASSIGNMENT_RISK ---

/** JPA entity — Jackson will deserialize every field. */
@Entity
class JavaOrderEntity {
    private Long id;
    private String customerId;
    private String status;

    Long getId() { return id; }
    String getCustomerId() { return customerId; }
    String getStatus() { return status; }
}

/** Safe: dedicated DTO with only the fields the caller may supply. */
class CreateJavaOrderRequest {
    String customerId;
    String status;
}

@interface Entity {}  // JPA @Entity stub

// Wave 4 annotation stubs
@interface Cacheable { String value() default ""; }
@interface CacheEvict { String value() default ""; }
@interface CachePut   { String value() default ""; }
@interface ExceptionHandler {}
@interface ResponseStatus { int value() default 500; }
@interface RestControllerAdvice {}
@interface ControllerAdvice {}

// Wave 5 annotation stubs
@interface Bean {}
@interface Around { String value() default ""; }
@interface Before { String value() default ""; }
@interface After  { String value() default ""; }
@interface AfterReturning { String value() default ""; }
@interface AfterThrowing  { String value() default ""; }
@interface FeignClient { String name() default ""; String value() default ""; String fallback() default ""; String fallbackFactory() default ""; }
@interface Configuration {}

/** Stub for Jackson ObjectMapper (subset of real API). */
class ObjectMapper {
    ObjectMapper() {}
    String writeValueAsString(Object value) throws Exception { return ""; }
    <T> T readValue(String content, Class<T> valueType) throws Exception { return null; }
}

/** Stub for ThreadPoolTaskScheduler (for safe fixture). */
class ThreadPoolTaskScheduler {
    void setPoolSize(int poolSize) {}
    void setThreadNamePrefix(String threadNamePrefix) {}
    void setErrorHandler(java.util.function.Consumer<Throwable> errorHandler) {}
    void initialize() {}
}
