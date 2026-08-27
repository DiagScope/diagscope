package example.kotlin.parity

annotation class RestController
annotation class GetMapping(val value: String)
annotation class Scheduled(val cron: String = "", val fixedDelay: Long = -1L, val fixedRate: Long = -1L, val initialDelay: Long = -1L, val initialDelayString: String = "")
annotation class Autowired
annotation class Synchronized
annotation class Retryable(val include: Array<out kotlin.reflect.KClass<out Throwable>> = [])
annotation class Recover
annotation class CrossOrigin(val value: String = "", val origins: Array<String> = [])
annotation class Value(val value: String)
annotation class PostMapping(val value: String = "")
annotation class PutMapping(val value: String = "")
annotation class Timed(val value: String = "")
annotation class Service
annotation class Repository
annotation class KafkaListener(val topics: Array<String>, val errorHandler: String = "")
annotation class KafkaHandler
annotation class Transactional(val propagation: Propagation = Propagation.REQUIRED, val readOnly: Boolean = false)

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

class RestTemplate {
    fun getForObject(url: String): String? = null
    fun postForObject(url: String, body: Any?): String? = null
}

// --- JPA stubs (for MISSING_TRANSACTION_ANNOTATION) ---
interface UserRepository {
    fun save(entity: Any): Any
    fun delete(entity: Any)
    fun deleteById(id: String)
}

// --- Application event stubs (for OUTBOX_PATTERN_MISSING / suppression) ---
interface ApplicationEventPublisher { fun publishEvent(event: Any) }

// --- Security config stubs (for SECRET_IN_STRING_LITERAL) ---
interface DataSourceBuilder {
    fun url(url: String): DataSourceBuilder
    fun setPassword(password: String): DataSourceBuilder
}

// --- Coroutine stubs (for COROUTINE_EXCEPTION_NOT_HANDLED, FLOW_EXCEPTION_NOT_CAUGHT) ---
interface CoroutineExceptionHandler

fun CoroutineExceptionHandler(handler: (Any, Throwable) -> Unit): CoroutineExceptionHandler =
    throw UnsupportedOperationException()

object GlobalScope {
    fun launch(context: Any = Unit, block: () -> Unit) {}
    fun async(context: Any = Unit, block: () -> Unit) {}
}

interface Flow<T> {
    fun collect(collector: (T) -> Unit)
    fun catch(block: (Throwable) -> Unit): Flow<T>
    fun launchIn(scope: Any)
}

fun <T> flowOf(vararg items: T): Flow<T> = throw UnsupportedOperationException()

object Dispatchers {
    val IO: Any = object {}
    val Default: Any = object {}
    val Main: Any = object {}
}

suspend fun <T> withContext(context: Any, block: () -> T): T = throw UnsupportedOperationException()

// --- Stubs for TRANSACTIONAL_ON_INTERFACE, MISSING_PAGINATION, KAFKA_RETRY_WITHOUT_BACKOFF ---

// Pageable stub (for MISSING_PAGINATION suppression)
interface Pageable

// KotlinOrderRepository for MISSING_PAGINATION fixture
interface KotlinOrderRepository {
    fun findAll(): List<String>                // triggers MISSING_PAGINATION
    fun findAll(pageable: Pageable): List<String>  // suppressed — has Pageable
    fun findByStatus(status: String): List<String>  // triggers MISSING_PAGINATION
    fun findById(id: String): String           // suppressed — not a collection
    fun countByStatus(status: String): Long    // suppressed — has "count"
}

// FixedBackOff and ExponentialBackOff stubs (for KAFKA_RETRY_WITHOUT_BACKOFF)
class FixedBackOff(val interval: Long = 0L, val maxAttempts: Long = Long.MAX_VALUE)
class ExponentialBackOff(val initialInterval: Long, val multiplier: Double) {
    var maxInterval: Long = 30000L
}

// --- Stubs for MASS_ASSIGNMENT_RISK ---

/** JPA entity — Jackson will deserialize every field. */
@Entity
class KotlinOrderEntity(
    val id: Long = 0L,
    val customerId: String = "",
    val status: String = ""
) {
    fun getId(): Long = id
    fun getCustomerId(): String = customerId
}

/** Safe: dedicated DTO with only the fields the caller may supply. */
class CreateKotlinOrderRequest(
    val customerId: String = "",
    val status: String = ""
)

annotation class Entity  // JPA @Entity stub

// Wave 4 annotation stubs
annotation class Cacheable(val value: String = "")
annotation class CacheEvict(val value: String = "")
annotation class CachePut(val value: String = "")
annotation class ExceptionHandler
annotation class ResponseStatus(val value: Int = 500)
annotation class RestControllerAdvice
annotation class ControllerAdvice

// Wave 5 annotation stubs
annotation class Bean
annotation class Around(val value: String = "")
annotation class Before(val value: String = "")
annotation class After(val value: String = "")
annotation class AfterReturning(val value: String = "")
annotation class AfterThrowing(val value: String = "")
annotation class FeignClient(val name: String = "", val value: String = "", val fallback: kotlin.reflect.KClass<*> = Void::class, val fallbackFactory: kotlin.reflect.KClass<*> = Void::class)
annotation class Configuration

/** Stub for Jackson ObjectMapper (subset of real API). */
class ObjectMapper {
    @Throws(Exception::class) fun writeValueAsString(value: Any?): String = ""
    @Throws(Exception::class) fun <T> readValue(content: String, valueType: Class<T>): T? = null
}

/** Stub for ThreadPoolTaskScheduler (for safe fixture). */
class ThreadPoolTaskScheduler {
    fun setPoolSize(poolSize: Int) {}
    fun setThreadNamePrefix(prefix: String) {}
    fun setErrorHandler(handler: (Throwable) -> Unit) {}
    fun initialize() {}
}
