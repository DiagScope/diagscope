package example.java.parity;

import java.io.File;
import java.io.InputStream;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;

/**
 * Fixture: covers the wave-11 rules BCRYPT_CREATED_PER_CALL, SIMPLE_DATE_FORMAT_PER_CALL, RANDOM_PER_CALL,
 * DELETE_ALL_WITHOUT_ID, CACHEABLE_ON_VOID_METHOD, SYSTEM_ENV_IN_BEAN, SENSITIVE_HEADER_LOGGED,
 * OPEN_REDIRECT_RISK, PATH_TRAVERSAL_RISK, TIMER_STARTED_NOT_STOPPED, OBJECT_INPUT_STREAM_RISK,
 * XML_EXTERNAL_ENTITY_RISK, TRANSACTIONAL_NO_ROLLBACK_FOR_EXCEPTION, SEMAPHORE_RELEASE_NOT_IN_FINALLY,
 * COUNT_DOWN_LATCH_NOT_IN_FINALLY, VIRTUAL_THREAD_WITH_THREAD_LOCAL, REFLECTIVE_ACCESS_IN_PRODUCTION,
 * EAGER_COLLECTION_FETCH, LAZY_BEAN_CIRCULAR_INDICATOR,
 * CACHE_EVICT_WITHOUT_ALL_ENTRIES_ON_VOID, MISSING_BULKHEAD_ON_HTTP_CLIENT, HTTP_CLIENT_NO_ERROR_HANDLER and
 * KAFKA_LISTENER_MANUAL_ACK_WRONG_MODE, REACTIVE_RESULT_DISCARDED, BLOCKING_CALL_ON_COMMON_POOL,
 * UNBOUNDED_THREAD_POOL and TIMER_TASK_EXCEPTION_KILLS_TIMER.
 */
@Service
class JavaWave11Service {

    private final JavaWave11OrderRepository orderRepository;
    private final ThreadLocal<String> holder = new ThreadLocal<>();
    private static final Logger log = null;

    JavaWave11Service(JavaWave11OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    // ── BCRYPT_CREATED_PER_CALL ──────────────────────────────────────────────

    /** BCRYPT_CREATED_PER_CALL: encoder built on every call. */
    Object hashPassword() {
        return new BCryptPasswordEncoder();
    }

    /** Safe: the factory method is where the encoder belongs. */
    @Bean
    Object passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // ── SIMPLE_DATE_FORMAT_PER_CALL / RANDOM_PER_CALL ────────────────────────

    /** SIMPLE_DATE_FORMAT_PER_CALL. */
    Object formatDate() {
        return new SimpleDateFormat("yyyy-MM-dd");
    }

    /** RANDOM_PER_CALL. */
    Object newToken() {
        return new SecureRandom();
    }

    /** Safe: an explicit seed is deliberate. */
    Object seeded() {
        return new java.util.Random(42L);
    }

    // ── DELETE_ALL_WITHOUT_ID ────────────────────────────────────────────────

    /** DELETE_ALL_WITHOUT_ID. */
    void resetOrders() {
        orderRepository.deleteAll();
    }

    /** Safe: single DELETE statement. */
    void resetOrdersInBatch() {
        orderRepository.deleteAllInBatch();
    }

    // ── CACHEABLE_ON_VOID_METHOD / CACHE_EVICT_WITHOUT_ALL_ENTRIES_ON_VOID ───

    /** CACHEABLE_ON_VOID_METHOD. */
    @Cacheable("orders")
    void refreshOrders() {
    }

    /** CACHE_EVICT_WITHOUT_ALL_ENTRIES_ON_VOID. */
    @CacheEvict("orders")
    void clearOrders() {
    }

    /** Safe: clears the whole cache. */
    @CacheEvict(value = "orders", allEntries = true)
    void clearAllOrders() {
    }

    // ── SYSTEM_ENV_IN_BEAN ───────────────────────────────────────────────────

    /** SYSTEM_ENV_IN_BEAN. */
    String apiUrl() {
        return System.getenv("API_URL");
    }

    // ── SENSITIVE_HEADER_LOGGED ──────────────────────────────────────────────

    /** SENSITIVE_HEADER_LOGGED. */
    void traceRequest(HttpServletRequest request) {
        log.info("auth {}", request.getHeader("Authorization"));
    }

    /** Safe: the message mentions the header but never reads its value. */
    void traceMissing(HttpServletRequest request) {
        log.warn("Missing Authorization header");
    }

    // ── TIMER_STARTED_NOT_STOPPED ────────────────────────────────────────────

    /** TIMER_STARTED_NOT_STOPPED. */
    void timedWork(MeterRegistry registry) {
        Timer.Sample sample = Timer.start(registry);
        process();
    }

    /** Safe: the sample is stopped. */
    void timedWorkSafe(MeterRegistry registry, Timer timer) {
        Timer.Sample sample = Timer.start(registry);
        process();
        sample.stop(timer);
    }

    // ── OBJECT_INPUT_STREAM_RISK / XML_EXTERNAL_ENTITY_RISK ──────────────────

    /** OBJECT_INPUT_STREAM_RISK. */
    Object readObject(InputStream in) throws Exception {
        return new ObjectInputStream(in);
    }

    /** XML_EXTERNAL_ENTITY_RISK. */
    Object parseXml() throws Exception {
        return DocumentBuilderFactory.newInstance();
    }

    /** XML_EXTERNAL_ENTITY_RISK: transformers resolve external entities too. */
    Object transformXml() throws Exception {
        return TransformerFactory.newInstance();
    }

    /** Safe: DTDs are disabled. */
    Object parseXmlSafe() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory;
    }

    // ── TRANSACTIONAL_NO_ROLLBACK_FOR_EXCEPTION ──────────────────────────────

    /** TRANSACTIONAL_NO_ROLLBACK_FOR_EXCEPTION. */
    @Transactional(noRollbackFor = Exception.class)
    void saveOrder() {
        process();
    }

    /** Safe: only a specific business exception keeps the commit. */
    @Transactional(noRollbackFor = OrderAlreadyExistsException.class)
    void saveOrderSafe() {
        process();
    }

    // ── SEMAPHORE_RELEASE_NOT_IN_FINALLY / COUNT_DOWN_LATCH_NOT_IN_FINALLY ───

    /** SEMAPHORE_RELEASE_NOT_IN_FINALLY. */
    void limited(Semaphore permits) throws InterruptedException {
        permits.acquire();
        process();
        permits.release();
    }

    /** Safe: the permit is returned in finally. */
    void limitedSafe(Semaphore permits) throws InterruptedException {
        permits.acquire();
        try {
            process();
        } finally {
            permits.release();
        }
    }

    /** COUNT_DOWN_LATCH_NOT_IN_FINALLY. */
    void task(CountDownLatch latch) {
        process();
        latch.countDown();
    }

    /** Safe: counted down in finally. */
    void taskSafe(CountDownLatch latch) {
        try {
            process();
        } finally {
            latch.countDown();
        }
    }

    // ── VIRTUAL_THREAD_WITH_THREAD_LOCAL / REFLECTIVE_ACCESS_IN_PRODUCTION ───

    /** VIRTUAL_THREAD_WITH_THREAD_LOCAL. */
    @RunOnVirtualThread
    void handleOnVirtualThread() {
        holder.set("request");
    }

    /** REFLECTIVE_ACCESS_IN_PRODUCTION. */
    void inject(java.lang.reflect.Field field) {
        field.setAccessible(true);
    }

    // ── HTTP_CLIENT_NO_ERROR_HANDLER ─────────────────────────────────────────

    /** HTTP_CLIENT_NO_ERROR_HANDLER. */
    RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /** Safe: the error policy is explicit. */
    RestTemplate restTemplateSafe() {
        RestTemplate template = new RestTemplate();
        template.setErrorHandler(new CustomErrorHandler());
        return template;
    }

    // ── KAFKA_LISTENER_MANUAL_ACK_WRONG_MODE ─────────────────────────────────

    /** KAFKA_LISTENER_MANUAL_ACK_WRONG_MODE. */
    @KafkaListener(topics = "wave11-orders")
    void onOrder(String payload, Acknowledgment acknowledgment) {
        acknowledgment.acknowledge();
    }

    /** Safe: the container factory is named. */
    @KafkaListener(topics = "wave11-orders-safe", containerFactory = "manualAckFactory")
    void onOrderSafe(String payload, Acknowledgment acknowledgment) {
        acknowledgment.acknowledge();
    }

    private void process() {
    }
}

// ── EAGER_COLLECTION_FETCH / JPA entity ──────────────────────────────────────

@Entity
class JavaWave11Order {

    /** EAGER_COLLECTION_FETCH. */
    @OneToMany(fetch = FetchType.EAGER)
    private List<String> items;

    @OneToMany(fetch = FetchType.LAZY)
    private List<String> tags;

    List<String> getItems() {
        return items;
    }
}

interface JavaWave11OrderRepository {
    void save(JavaWave11Order order);

    void deleteAll();

    void deleteAllInBatch();
}

// ── LAZY_BEAN_CIRCULAR_INDICATOR ─────────────────────────────────────────────

@Service
class JavaWave11LazyService {

    private final JavaWave11Service other;

    JavaWave11LazyService(@Lazy JavaWave11Service other) {
        this.other = other;
    }

    /** The @Lazy constructor parameter above is what LAZY_BEAN_CIRCULAR_INDICATOR reports. */
    void run() {
    }
}

// ── OPEN_REDIRECT_RISK / PATH_TRAVERSAL_RISK / MISSING_BULKHEAD_ON_HTTP_CLIENT

@RestController
class JavaWave11Controller {

    private final RestTemplate restTemplate;

    JavaWave11Controller(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** OPEN_REDIRECT_RISK. */
    @GetMapping("/wave11/go")
    void go(HttpServletResponse response, String returnUrl) throws Exception {
        response.sendRedirect(returnUrl);
    }

    /** Safe: the target is a constant. */
    @GetMapping("/wave11/home")
    void home(HttpServletResponse response) throws Exception {
        response.sendRedirect("/home");
    }

    /** PATH_TRAVERSAL_RISK. */
    @GetMapping("/wave11/file")
    Object file(String name) {
        return new File(name);
    }

    /** MISSING_BULKHEAD_ON_HTTP_CLIENT. */
    @GetMapping("/wave11/proxy")
    Object proxy() {
        return restTemplate.getForObject("http://downstream.example/api", String.class);
    }

    /** Safe: the call is isolated by a bulkhead. */
    @GetMapping("/wave11/proxy-safe")
    @Bulkhead(name = "downstream")
    Object proxySafe() {
        return restTemplate.getForObject("http://downstream.example/api", String.class);
    }
}

// ── REACTIVE_RESULT_DISCARDED ────────────────────────────────────────────────

@Service
class JavaWave11ReactiveService {

    Mono<String> save(String id) {
        return Mono.just(id);
    }

    /** REACTIVE_RESULT_DISCARDED: the Mono is created and dropped, so nothing is saved. */
    void fireAndForgetBug(String id) {
        save(id);
    }

    /** Safe: the pipeline is returned to the caller. */
    Mono<String> chained(String id) {
        return save(id);
    }
}

// ── BLOCKING_CALL_ON_COMMON_POOL / UNBOUNDED_THREAD_POOL / TIMER_TASK_EXCEPTION_KILLS_TIMER

@Service
class JavaWave11PoolService {

    /** BLOCKING_CALL_ON_COMMON_POOL: parallelStream blocks the shared pool. */
    void parallelBlocking(List<String> ids) {
        ids.parallelStream().forEach(id -> {
            LockSupport.parkNanos(1000);
        });
    }

    /** Safe: a sequential stream does not use the common pool. */
    void sequentialBlocking(List<String> ids) {
        ids.stream().forEach(id -> {
            LockSupport.parkNanos(1000);
        });
    }

    /** BLOCKING_CALL_ON_COMMON_POOL: supplyAsync without an executor. */
    Object asyncBlocking() {
        return CompletableFuture.supplyAsync(() -> {
            LockSupport.parkNanos(1000);
            return "done";
        });
    }

    /** Safe: a dedicated executor is given. */
    Object asyncBlockingSafe(Executor ioExecutor) {
        return CompletableFuture.supplyAsync(() -> {
            LockSupport.parkNanos(1000);
            return "done";
        }, ioExecutor);
    }

    /** UNBOUNDED_THREAD_POOL. */
    Object cachedPool() {
        return Executors.newCachedThreadPool();
    }

    /** Safe: a fixed pool. */
    Object fixedPool() {
        return Executors.newFixedThreadPool(4);
    }

    /** TIMER_TASK_EXCEPTION_KILLS_TIMER. */
    Object legacyTimer() {
        return new Timer(true);
    }
}
