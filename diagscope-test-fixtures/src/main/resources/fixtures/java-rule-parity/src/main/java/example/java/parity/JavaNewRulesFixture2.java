package example.java.parity;

import java.io.IOException;

/**
 * Fixture: covers RETRY_ON_ALL_EXCEPTIONS, VALUE_WITHOUT_DEFAULT,
 * ENTITY_EXPOSED_IN_REST_RESPONSE, TRANSACTION_WITH_HTTP_CALL,
 * HTTP_CLIENT_CREATED_PER_REQUEST, CORS_WILDCARD_ORIGIN, KAFKA_TOPIC_HARDCODED.
 */

// ── RETRY_ON_ALL_EXCEPTIONS ────────────────────────────────────────────────────

/**
 * Service that uses @Retryable with and without an exception filter.
 */
@Service
class JavaRetryService {

    private final JavaRepository repository;

    JavaRetryService(JavaRepository repository) {
        this.repository = repository;
    }

    /**
     * RETRY_ON_ALL_EXCEPTIONS: @Retryable with no include/value attribute retries on every
     * exception — including NullPointerException and other programming defects.
     */
    @Retryable
    void processPayment(Object entity) { // → RETRY_ON_ALL_EXCEPTIONS
        repository.save(entity.toString());
    }

    /**
     * Safe: @Retryable scoped to IOException — only transient failures are retried.
     */
    @Retryable(include = {IOException.class})
    void processPaymentSafe(Object entity) { // safe — scoped to IOException
        repository.save(entity.toString());
    }
}

// ── VALUE_WITHOUT_DEFAULT ──────────────────────────────────────────────────────

/**
 * Service demonstrating @Value setter injection with and without a default.
 */
@Service
class JavaConfigService {

    /**
     * VALUE_WITHOUT_DEFAULT: if 'payment.api.url' is absent, Spring throws at startup.
     */
    @Value("${payment.api.url}")
    void setApiUrl(String url) {} // → VALUE_WITHOUT_DEFAULT

    /**
     * Safe: fallback URL prevents startup failure when the property is missing.
     */
    @Value("${payment.api.url:https://api.example.com}")
    void setApiUrlSafe(String url) {} // safe — has default value
}

// ── ENTITY_EXPOSED_IN_REST_RESPONSE ───────────────────────────────────────────

/**
 * Controller demonstrating entity vs DTO in REST responses.
 * The entity return type is already triggered by JavaOrderWriteController.getOrder()
 * in JavaSecurityAndExceptionFixture.java — this adds a second example for clarity.
 */
@RestController
class JavaEntityResponseController {

    private final JavaRepository repository;

    JavaEntityResponseController(JavaRepository repository) {
        this.repository = repository;
    }

    /**
     * ENTITY_EXPOSED_IN_REST_RESPONSE: returns the JPA entity, exposing all mapped fields.
     */
    @GetMapping("/entity-orders/{id}")
    JavaOrderEntity getEntityOrder(String id) { // → ENTITY_EXPOSED_IN_REST_RESPONSE
        return new JavaOrderEntity();
    }

    /**
     * Safe: returns a dedicated DTO — only the fields the client is allowed to see.
     */
    @GetMapping("/dto-orders/{id}")
    CreateJavaOrderRequest getOrderDto(String id) { // safe — DTO, not entity
        return new CreateJavaOrderRequest();
    }
}

// ── TRANSACTION_WITH_HTTP_CALL ─────────────────────────────────────────────────

/**
 * Service demonstrating a @Transactional method making an HTTP call.
 */
@Service
class JavaTxHttpService {

    private final JavaRepository repository;
    private final RestTemplate restTemplate;

    JavaTxHttpService(JavaRepository repository, RestTemplate restTemplate) {
        this.repository = repository;
        this.restTemplate = restTemplate;
    }

    /**
     * TRANSACTION_WITH_HTTP_CALL: DB connection is held open for the entire HTTP roundtrip.
     */
    @Transactional
    void placeOrder(Object entity) { // → TRANSACTION_WITH_HTTP_CALL
        repository.save(entity.toString());
        restTemplate.getForObject("https://api.example.com/notify"); // HTTP inside transaction
    }

    /**
     * Safe: HTTP call is performed outside the transaction boundary.
     */
    void placeOrderSafe(Object entity) {
        restTemplate.getForObject("https://api.example.com/notify");
        persistOrder(entity);
    }

    @Transactional
    void persistOrder(Object entity) { // safe — no HTTP call inside
        repository.save(entity.toString());
    }
}

// ── HTTP_CLIENT_CREATED_PER_REQUEST ───────────────────────────────────────────

/**
 * Service demonstrating per-call vs shared HTTP client construction.
 */
@Service
class JavaHttpClientService {

    private final RestTemplate sharedRestTemplate;

    JavaHttpClientService(RestTemplate sharedRestTemplate) {
        this.sharedRestTemplate = sharedRestTemplate;
    }

    /**
     * HTTP_CLIENT_CREATED_PER_REQUEST: new RestTemplate() on each call allocates a new
     * thread pool and connection pool.
     */
    String fetchData(String url) { // → HTTP_CLIENT_CREATED_PER_REQUEST
        RestTemplate client = new RestTemplate();
        return client.getForObject(url);
    }

    /**
     * Safe: uses the shared RestTemplate injected at construction time.
     */
    String fetchDataSafe(String url) { // safe — shared bean
        return sharedRestTemplate.getForObject(url);
    }
}

// ── CORS_WILDCARD_ORIGIN ───────────────────────────────────────────────────────

/**
 * Controller demonstrating wildcard vs specific CORS origins.
 */
@RestController
class JavaCorsController {

    /**
     * CORS_WILDCARD_ORIGIN: any browser origin can access this endpoint — CSRF risk.
     */
    @CrossOrigin("*")
    @GetMapping("/public/orders")
    String getAllOrders() { // → CORS_WILDCARD_ORIGIN
        return "orders";
    }

    /**
     * Safe: only specific trusted origins are allowed.
     */
    @CrossOrigin("https://app.example.com")
    @GetMapping("/trusted/orders")
    String getAllOrdersTrusted() { // safe — specific origin
        return "orders";
    }
}

// ── KAFKA_TOPIC_HARDCODED ──────────────────────────────────────────────────────

/**
 * Additional listener demonstrating hardcoded vs property-based topic names.
 * (Other hardcoded examples also exist in JavaNewRulesFixture.java.)
 */
@Service
class JavaKafkaListenerService2 {

    /**
     * KAFKA_TOPIC_HARDCODED: literal "invoice-events" bakes the topic name into the artifact.
     */
    @KafkaListener(topics = {"invoice-events"})
    void onInvoiceEvent(String message) {} // → KAFKA_TOPIC_HARDCODED

    /**
     * Safe: topic name from property placeholder — works across all environments.
     */
    @KafkaListener(topics = {"${kafka.topics.invoice}"})
    void onInvoiceEventSafe(String message) {} // safe — property placeholder
}

// ── Controller entrypoint making all fixture methods reachable ────────────────

@RestController
class JavaNewRules2Controller {

    private final JavaRetryService retryService;
    private final JavaEntityResponseController entityController;
    private final JavaTxHttpService txHttpService;
    private final JavaHttpClientService httpClientService;
    private final JavaCorsController corsController;

    JavaNewRules2Controller(
            JavaRetryService retryService,
            JavaEntityResponseController entityController,
            JavaTxHttpService txHttpService,
            JavaHttpClientService httpClientService,
            JavaCorsController corsController) {
        this.retryService = retryService;
        this.entityController = entityController;
        this.txHttpService = txHttpService;
        this.httpClientService = httpClientService;
        this.corsController = corsController;
    }

    @GetMapping("/java-new-rules-2")
    String trigger(String id) {
        retryService.processPayment(new Object());
        retryService.processPaymentSafe(new Object());
        txHttpService.placeOrder(new Object());
        txHttpService.placeOrderSafe(new Object());
        httpClientService.fetchData(id);
        httpClientService.fetchDataSafe(id);
        corsController.getAllOrders();
        corsController.getAllOrdersTrusted();
        entityController.getEntityOrder(id);
        entityController.getOrderDto(id);
        return id;
    }
}
