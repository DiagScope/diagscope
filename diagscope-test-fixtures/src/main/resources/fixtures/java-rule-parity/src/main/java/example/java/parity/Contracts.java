package example.java.parity;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

@interface RestController {}
@interface GetMapping { String value(); }
@interface Scheduled { String cron() default ""; }
@interface Retryable {}
@interface Recover {}
@interface Timed { String value() default ""; }
@interface Service {}
@interface Repository {}
@interface KafkaListener { String[] topics(); }
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

interface ExecutorService { Completion submit(Runnable task); }
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
