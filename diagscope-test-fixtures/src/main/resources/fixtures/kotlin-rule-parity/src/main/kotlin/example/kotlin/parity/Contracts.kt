package example.kotlin.parity

annotation class RestController
annotation class GetMapping(val value: String)
annotation class Scheduled(val cron: String = "", val fixedDelay: Long = -1L)
annotation class Retryable
annotation class Recover
annotation class Timed(val value: String = "")
annotation class Service
annotation class Repository
annotation class KafkaListener(val topics: Array<String>, val errorHandler: String = "")
annotation class KafkaHandler
annotation class Transactional(val propagation: Propagation = Propagation.REQUIRED)

enum class Propagation {
    REQUIRED,
    REQUIRES_NEW,
    MANDATORY
}

interface Logger {
    fun info(message: String, vararg arguments: Any?)
    fun warn(message: String, vararg arguments: Any?)
    fun error(message: String, vararg arguments: Any?)
}

interface Completion {
    fun whenComplete(callback: (Any?, Throwable?) -> Unit): Completion
}

interface ExecutorService {
    fun submit(task: () -> Unit): Completion
    fun shutdown()
    fun shutdownNow()
}

object Executors {
    fun newFixedThreadPool(nThreads: Int): ExecutorService = throw UnsupportedOperationException()
    fun newCachedThreadPool(): ExecutorService = throw UnsupportedOperationException()
    fun newSingleThreadExecutor(): ExecutorService = throw UnsupportedOperationException()
}

interface KafkaTemplate {
    fun send(topic: String, payload: String): Completion
}

interface Acknowledgment {
    fun acknowledge()
}

interface RemoteResponse {
    fun onErrorReturn(value: String): RemoteResponse
    fun onErrorResume(handler: (Throwable) -> String): RemoteResponse
}

interface RemoteClient {
    fun refresh()
    fun remote(): RemoteResponse
    fun describeFailure(failure: Throwable): String
}

interface DataSource {
    fun getConnection(): Connection
}

interface Connection : AutoCloseable {
    fun prepareStatement(sql: String): PreparedStatement
}

interface PreparedStatement : AutoCloseable {
    fun executeQuery(): ResultSet
}

interface ResultSet : AutoCloseable

interface EntityManagerFactory {
    fun createEntityManager(): EntityManager
}

interface EntityManager : AutoCloseable {
    fun find(type: Any, id: String): Any?
}

interface JdbcTemplate {
    fun getDataSource(): DataSource
}

object MDC {
    fun put(key: String, value: String) {}
    fun remove(key: String) {}
    fun clear() {}
    fun getCopyOfContextMap(): Map<String, String> = emptyMap()
    fun setContextMap(context: Map<String, String>) {}
}

annotation class Async
annotation class Incoming(val value: String)
annotation class NonBlocking

// --- Tracing stubs (for SPAN_NOT_CLOSED) ---
interface Span { fun end() }
interface Tracer { fun startSpan(name: String): Span }

interface Lock {
    fun lock()
    fun unlock()
    fun tryLock(): Boolean
}

class CompletableFuture<T> {
    @Throws(Exception::class) fun get(): T = throw Exception()
    @Throws(Exception::class) fun get(timeout: Long, unit: java.util.concurrent.TimeUnit): T = throw Exception()
    fun join(): T = throw Exception()
}

class ThreadLocal<T> {
    fun set(value: T) {}
    fun get(): T? = null
    fun remove() {}
}

interface Uni<T> {
    fun onFailure(): Uni<T>
    fun recoverWithItem(item: T): Uni<T>
    fun recoverWithItem(recovery: (Throwable) -> T): Uni<T>
    fun subscribe(): Subscription<T>
}

interface Subscription<T> {
    fun with(item: (T) -> Unit)
    fun with(item: (T) -> Unit, failure: (Throwable) -> Unit)
}

// --- HTTP / reactive stubs (for HTTP_TIMEOUT_NOT_SET) ---
interface Mono<T> {
    fun block(): T
    fun timeout(duration: Any): Mono<T>
    fun map(mapper: (T) -> T): Mono<T>
}

interface WebClient {
    fun get(uri: String): Mono<String>
}

// --- JPA stubs (for MISSING_TRANSACTION_ANNOTATION) ---
interface UserRepository {
    fun save(entity: Any): Any
    fun delete(entity: Any)
    fun deleteById(id: String)
}
