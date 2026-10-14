package example.kotlin.parity

import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore

/**
 * Fixture: covers the wave-11 rules BCRYPT_CREATED_PER_CALL, SIMPLE_DATE_FORMAT_PER_CALL, RANDOM_PER_CALL,
 * DELETE_ALL_WITHOUT_ID, CACHEABLE_ON_VOID_METHOD, SYSTEM_ENV_IN_BEAN, SENSITIVE_HEADER_LOGGED,
 * OPEN_REDIRECT_RISK, PATH_TRAVERSAL_RISK, TIMER_STARTED_NOT_STOPPED, OBJECT_INPUT_STREAM_RISK,
 * XML_EXTERNAL_ENTITY_RISK, TRANSACTIONAL_NO_ROLLBACK_FOR_EXCEPTION, SEMAPHORE_RELEASE_NOT_IN_FINALLY,
 * COUNT_DOWN_LATCH_NOT_IN_FINALLY, VIRTUAL_THREAD_WITH_THREAD_LOCAL, REFLECTIVE_ACCESS_IN_PRODUCTION,
 * EAGER_COLLECTION_FETCH, LAZY_BEAN_CIRCULAR_INDICATOR,
 * CACHE_EVICT_WITHOUT_ALL_ENTRIES_ON_VOID, MISSING_BULKHEAD_ON_HTTP_CLIENT, HTTP_CLIENT_NO_ERROR_HANDLER and
 * KAFKA_LISTENER_MANUAL_ACK_WRONG_MODE, CANCELLATION_EXCEPTION_SWALLOWED, REACTIVE_RESULT_DISCARDED,
 * BLOCKING_CALL_ON_COMMON_POOL, UNBOUNDED_THREAD_POOL and TIMER_TASK_EXCEPTION_KILLS_TIMER.
 */
@Service
open class KotlinWave11Service(private val orderRepository: KotlinWave11OrderRepository) {

    private val holder: ThreadLocal<String> = ThreadLocal()
    private val log: Logger = LoggerFactory.getLogger(KotlinWave11Service::class.java)

    // ── BCRYPT_CREATED_PER_CALL ──────────────────────────────────────────────

    /** BCRYPT_CREATED_PER_CALL: encoder built on every call. */
    open fun hashPassword(): Any = BCryptPasswordEncoder()

    /** Safe: the factory method is where the encoder belongs. */
    @Bean
    open fun passwordEncoder(): Any = BCryptPasswordEncoder()

    // ── SIMPLE_DATE_FORMAT_PER_CALL / RANDOM_PER_CALL ────────────────────────

    /** SIMPLE_DATE_FORMAT_PER_CALL. */
    open fun formatDate(): Any = SimpleDateFormat("yyyy-MM-dd")

    /** RANDOM_PER_CALL. */
    open fun newToken(): Any = SecureRandom()

    /** Safe: an explicit seed is deliberate. */
    open fun seeded(): Any = java.util.Random(42L)

    // ── DELETE_ALL_WITHOUT_ID ────────────────────────────────────────────────

    /** DELETE_ALL_WITHOUT_ID. */
    open fun resetOrders() {
        orderRepository.deleteAll()
    }

    /** Safe: single DELETE statement. */
    open fun resetOrdersInBatch() {
        orderRepository.deleteAllInBatch()
    }

    // ── CACHEABLE_ON_VOID_METHOD / CACHE_EVICT_WITHOUT_ALL_ENTRIES_ON_VOID ───

    /** CACHEABLE_ON_VOID_METHOD. */
    @Cacheable("orders")
    open fun refreshOrders(): Unit {
    }

    /** CACHE_EVICT_WITHOUT_ALL_ENTRIES_ON_VOID. */
    @CacheEvict("orders")
    open fun clearOrders() {
    }

    /** Safe: clears the whole cache. */
    @CacheEvict(value = ["orders"], allEntries = true)
    open fun clearAllOrders() {
    }

    // ── SYSTEM_ENV_IN_BEAN ───────────────────────────────────────────────────

    /** SYSTEM_ENV_IN_BEAN. */
    open fun apiUrl(): String? = System.getenv("API_URL")

    // ── SENSITIVE_HEADER_LOGGED ──────────────────────────────────────────────

    /** SENSITIVE_HEADER_LOGGED. */
    open fun traceRequest(request: HttpServletRequest) {
        log.info("auth {}", request.getHeader("Authorization"))
    }

    /** Safe: the message mentions the header but never reads its value. */
    open fun traceMissing(request: HttpServletRequest) {
        log.warn("Missing Authorization header")
    }

    // ── TIMER_STARTED_NOT_STOPPED ────────────────────────────────────────────

    /** TIMER_STARTED_NOT_STOPPED. */
    open fun timedWork(registry: MeterRegistry) {
        val sample = Timer.start(registry)
        process()
    }

    /** Safe: the sample is stopped. */
    open fun timedWorkSafe(registry: MeterRegistry, timer: Timer) {
        val sample = Timer.start(registry)
        process()
        sample.stop(timer)
    }

    // ── OBJECT_INPUT_STREAM_RISK / XML_EXTERNAL_ENTITY_RISK ──────────────────

    /** OBJECT_INPUT_STREAM_RISK. */
    open fun readObject(input: InputStream): Any = ObjectInputStream(input)

    /** XML_EXTERNAL_ENTITY_RISK. */
    open fun parseXml(): Any = DocumentBuilderFactory.newInstance()

    /** XML_EXTERNAL_ENTITY_RISK: transformers resolve external entities too. */
    open fun transformXml(): Any = TransformerFactory.newInstance()

    /** Safe: DTDs are disabled. */
    open fun parseXmlSafe(): Any {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return factory
    }

    // ── TRANSACTIONAL_NO_ROLLBACK_FOR_EXCEPTION ──────────────────────────────

    /** TRANSACTIONAL_NO_ROLLBACK_FOR_EXCEPTION. */
    @Transactional(noRollbackFor = [Exception::class])
    open fun saveOrder() {
        process()
    }

    /** Safe: only a specific business exception keeps the commit. */
    @Transactional(noRollbackFor = [OrderAlreadyExistsException::class])
    open fun saveOrderSafe() {
        process()
    }

    // ── SEMAPHORE_RELEASE_NOT_IN_FINALLY / COUNT_DOWN_LATCH_NOT_IN_FINALLY ───

    /** SEMAPHORE_RELEASE_NOT_IN_FINALLY. */
    open fun limited(permits: Semaphore) {
        permits.acquire()
        process()
        permits.release()
    }

    /** Safe: the permit is returned in finally. */
    open fun limitedSafe(permits: Semaphore) {
        permits.acquire()
        try {
            process()
        } finally {
            permits.release()
        }
    }

    /** COUNT_DOWN_LATCH_NOT_IN_FINALLY. */
    open fun task(latch: CountDownLatch) {
        process()
        latch.countDown()
    }

    /** Safe: counted down in finally. */
    open fun taskSafe(latch: CountDownLatch) {
        try {
            process()
        } finally {
            latch.countDown()
        }
    }

    // ── VIRTUAL_THREAD_WITH_THREAD_LOCAL / REFLECTIVE_ACCESS_IN_PRODUCTION ───

    /** VIRTUAL_THREAD_WITH_THREAD_LOCAL. */
    @RunOnVirtualThread
    open fun handleOnVirtualThread() {
        holder.set("request")
    }

    /** REFLECTIVE_ACCESS_IN_PRODUCTION. */
    open fun inject(field: java.lang.reflect.Field) {
        field.setAccessible(true)
    }

    /** REFLECTIVE_ACCESS_IN_PRODUCTION: Kotlin's property syntax for the same call. */
    open fun injectKotlinStyle(field: java.lang.reflect.Field) {
        field.isAccessible = true
    }

    // ── HTTP_CLIENT_NO_ERROR_HANDLER ─────────────────────────────────────────

    /** HTTP_CLIENT_NO_ERROR_HANDLER. */
    open fun restTemplate(): RestTemplate = RestTemplate()

    /** Safe: the error policy is explicit. */
    open fun restTemplateSafe(): RestTemplate {
        val template = RestTemplate()
        template.setErrorHandler(CustomErrorHandler())
        return template
    }

    // ── KAFKA_LISTENER_MANUAL_ACK_WRONG_MODE ─────────────────────────────────

    /** KAFKA_LISTENER_MANUAL_ACK_WRONG_MODE. */
    @KafkaListener(topics = ["wave11-orders"])
    open fun onOrder(payload: String, acknowledgment: Acknowledgment) {
        acknowledgment.acknowledge()
    }

    /** Safe: the container factory is named. */
    @KafkaListener(topics = ["wave11-orders-safe"], containerFactory = "manualAckFactory")
    open fun onOrderSafe(payload: String, acknowledgment: Acknowledgment) {
        acknowledgment.acknowledge()
    }

    private fun process() {
    }
}

// ── EAGER_COLLECTION_FETCH / JPA entity ──────────────────────────────────────

@Entity
open class KotlinWave11Order(
    /** EAGER_COLLECTION_FETCH. */
    @OneToMany(fetch = FetchType.EAGER) val items: List<String> = emptyList(),
    @OneToMany(fetch = FetchType.LAZY) val tags: List<String> = emptyList()
) {
    open fun count(): Int = items.size
}

interface KotlinWave11OrderRepository {
    fun save(order: KotlinWave11Order)

    fun deleteAll()

    fun deleteAllInBatch()
}

// ── LAZY_BEAN_CIRCULAR_INDICATOR ─────────────────────────────────────────────

@Service
open class KotlinWave11LazyService(@Lazy private val other: KotlinWave11Service) {

    /** The @Lazy constructor parameter above is what LAZY_BEAN_CIRCULAR_INDICATOR reports. */
    open fun run() {
    }
}

// ── OPEN_REDIRECT_RISK / PATH_TRAVERSAL_RISK / MISSING_BULKHEAD_ON_HTTP_CLIENT

@RestController
open class KotlinWave11Controller(private val restTemplate: RestTemplate) {

    /** OPEN_REDIRECT_RISK. */
    @GetMapping("/wave11/go")
    open fun go(response: HttpServletResponse, returnUrl: String) {
        response.sendRedirect(returnUrl)
    }

    /** Safe: the target is a constant. */
    @GetMapping("/wave11/home")
    open fun home(response: HttpServletResponse) {
        response.sendRedirect("/home")
    }

    /** PATH_TRAVERSAL_RISK. */
    @GetMapping("/wave11/file")
    open fun file(name: String): Any = File(name)

    /** MISSING_BULKHEAD_ON_HTTP_CLIENT. */
    @GetMapping("/wave11/proxy")
    open fun proxy(): Any? = restTemplate.getForObject("http://downstream.example/api", String::class.java)

    /** Safe: the call is isolated by a bulkhead. */
    @GetMapping("/wave11/proxy-safe")
    @Bulkhead(name = "downstream")
    open fun proxySafe(): Any? = restTemplate.getForObject("http://downstream.example/api", String::class.java)
}

// ── CANCELLATION_EXCEPTION_SWALLOWED ─────────────────────────────────────────

@Service
open class KotlinWave11CoroutineService {

    /** CANCELLATION_EXCEPTION_SWALLOWED: a cancelled coroutine falls through to the fallback. */
    open suspend fun load(): String {
        try {
            return fetch()
        } catch (e: Exception) {
            return "fallback"
        }
    }

    /** Safe: cancellation is rethrown before the broad catch. */
    open suspend fun loadSafe(): String {
        try {
            return fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "fallback"
        }
    }

    /** CANCELLATION_EXCEPTION_SWALLOWED: runCatching also catches cancellation. */
    open suspend fun loadOrNull(): String? = runCatching { fetch() }.getOrNull()

    private suspend fun fetch(): String = "value"
}

// ── REACTIVE_RESULT_DISCARDED ────────────────────────────────────────────────

@Service
open class KotlinWave11ReactiveService {

    open fun save(id: String): Mono<String> = Mono.just(id)

    /** REACTIVE_RESULT_DISCARDED: the Mono is created and dropped, so nothing is saved. */
    open fun fireAndForgetBug(id: String) {
        save(id)
    }

    /** Safe: the pipeline is returned to the caller. */
    open fun chained(id: String): Mono<String> = save(id)
}

// ── BLOCKING_CALL_ON_COMMON_POOL / UNBOUNDED_THREAD_POOL / TIMER_TASK_EXCEPTION_KILLS_TIMER

@Service
open class KotlinWave11PoolService {

    /** BLOCKING_CALL_ON_COMMON_POOL: parallelStream blocks the shared pool. */
    open fun parallelBlocking(ids: List<String>) {
        ids.parallelStream().forEach {
            LockSupport.parkNanos(1000)
        }
    }

    /** Safe: a sequential stream does not use the common pool. */
    open fun sequentialBlocking(ids: List<String>) {
        ids.stream().forEach {
            LockSupport.parkNanos(1000)
        }
    }

    /** BLOCKING_CALL_ON_COMMON_POOL: supplyAsync without an executor. */
    open fun asyncBlocking(): Any = CompletableFuture.supplyAsync {
        LockSupport.parkNanos(1000)
        "done"
    }

    /** UNBOUNDED_THREAD_POOL. */
    open fun cachedPool(): Any = Executors.newCachedThreadPool()

    /** Safe: a fixed pool. */
    open fun fixedPool(): Any = Executors.newFixedThreadPool(4)

    /** TIMER_TASK_EXCEPTION_KILLS_TIMER. */
    open fun legacyTimer(): Any = Timer(true)
}
