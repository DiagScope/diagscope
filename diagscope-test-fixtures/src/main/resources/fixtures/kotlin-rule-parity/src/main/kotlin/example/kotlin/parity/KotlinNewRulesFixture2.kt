package example.kotlin.parity

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
class KotlinRetryService(private val repository: KotlinOrderRepository) {

    /**
     * RETRY_ON_ALL_EXCEPTIONS: @Retryable with no include attribute retries on every exception.
     */
    @Retryable
    fun processPayment(entity: Any) { // → RETRY_ON_ALL_EXCEPTIONS
        repository.findAll()
    }

    /**
     * Safe: @Retryable scoped to IOException — only transient failures are retried.
     */
    @Retryable(include = [java.io.IOException::class])
    fun processPaymentSafe(entity: Any) { // safe — scoped to IOException
        repository.findAll()
    }
}

// ── VALUE_WITHOUT_DEFAULT ──────────────────────────────────────────────────────

/**
 * Service demonstrating @Value setter injection with and without a default.
 */
@Service
class KotlinConfigService {

    /**
     * VALUE_WITHOUT_DEFAULT: if 'payment.api.url' is absent, Spring throws at startup.
     */
    @Value("\${payment.api.url}")
    fun setApiUrl(url: String) {} // → VALUE_WITHOUT_DEFAULT

    /**
     * Safe: fallback URL prevents startup failure when the property is missing.
     */
    @Value("\${payment.api.url:https://api.example.com}")
    fun setApiUrlSafe(url: String) {} // safe — has default value
}

// ── ENTITY_EXPOSED_IN_REST_RESPONSE ───────────────────────────────────────────

/**
 * Controller demonstrating entity vs DTO in REST responses.
 */
@RestController
class KotlinEntityResponseController(private val repository: KotlinOrderRepository) {

    /**
     * ENTITY_EXPOSED_IN_REST_RESPONSE: returns the JPA entity, exposing all mapped fields.
     */
    @GetMapping("/kotlin/entity-orders/{id}")
    fun getEntityOrder(id: String): KotlinOrderEntity { // → ENTITY_EXPOSED_IN_REST_RESPONSE
        return KotlinOrderEntity()
    }

    /**
     * Safe: returns a dedicated DTO.
     */
    @GetMapping("/kotlin/dto-orders/{id}")
    fun getOrderDto(id: String): CreateKotlinOrderRequest { // safe — DTO
        return CreateKotlinOrderRequest()
    }
}

// ── TRANSACTION_WITH_HTTP_CALL ─────────────────────────────────────────────────

/**
 * Service demonstrating a @Transactional method making an HTTP call.
 */
@Service
class KotlinTxHttpService(
    private val repository: KotlinOrderRepository,
    private val restTemplate: RestTemplate
) {

    /**
     * TRANSACTION_WITH_HTTP_CALL: DB connection is held open for the entire HTTP roundtrip.
     */
    @Transactional
    fun placeOrder(entity: Any) { // → TRANSACTION_WITH_HTTP_CALL
        repository.findAll()
        restTemplate.getForObject("https://api.example.com/notify") // HTTP inside transaction
    }

    /**
     * Safe: HTTP call is performed outside the transaction boundary.
     */
    fun placeOrderSafe(entity: Any) {
        restTemplate.getForObject("https://api.example.com/notify")
        persistOrder(entity)
    }

    @Transactional
    fun persistOrder(entity: Any) { // safe — no HTTP call inside
        repository.findAll()
    }
}

// ── HTTP_CLIENT_CREATED_PER_REQUEST ───────────────────────────────────────────

/**
 * Service demonstrating per-call vs shared HTTP client construction.
 */
@Service
class KotlinHttpClientService(private val sharedRestTemplate: RestTemplate) {

    /**
     * HTTP_CLIENT_CREATED_PER_REQUEST: new RestTemplate() on each call.
     */
    fun fetchData(url: String): String? { // → HTTP_CLIENT_CREATED_PER_REQUEST
        val client = RestTemplate()
        return client.getForObject(url)
    }

    /**
     * Safe: uses the shared RestTemplate injected at construction time.
     */
    fun fetchDataSafe(url: String): String? { // safe — shared bean
        return sharedRestTemplate.getForObject(url)
    }
}

// ── CORS_WILDCARD_ORIGIN ───────────────────────────────────────────────────────

/**
 * Controller demonstrating wildcard vs specific CORS origins.
 */
@RestController
class KotlinCorsController {

    /**
     * CORS_WILDCARD_ORIGIN: any browser origin can access this endpoint — CSRF risk.
     */
    @CrossOrigin("*")
    @GetMapping("/kotlin/public/orders")
    fun getAllOrders(): String { // → CORS_WILDCARD_ORIGIN
        return "orders"
    }

    /**
     * Safe: only specific trusted origins are allowed.
     */
    @CrossOrigin("https://app.example.com")
    @GetMapping("/kotlin/trusted/orders")
    fun getAllOrdersTrusted(): String { // safe — specific origin
        return "orders"
    }
}

// ── KAFKA_TOPIC_HARDCODED ──────────────────────────────────────────────────────

/**
 * Additional listener demonstrating hardcoded vs property-based topic names.
 */
@Service
class KotlinKafkaListenerService2 {

    /**
     * KAFKA_TOPIC_HARDCODED: literal "invoice-events" bakes the topic into the artifact.
     */
    @KafkaListener(topics = ["invoice-events"])
    fun onInvoiceEvent(message: String) {} // → KAFKA_TOPIC_HARDCODED

    /**
     * Safe: topic name from property placeholder.
     */
    @KafkaListener(topics = ["\${kafka.topics.invoice}"])
    fun onInvoiceEventSafe(message: String) {} // safe — property placeholder
}

// ── Controller entrypoint making all fixture methods reachable ────────────────

@RestController
class KotlinNewRules2Controller(
    private val retryService: KotlinRetryService,
    private val entityController: KotlinEntityResponseController,
    private val txHttpService: KotlinTxHttpService,
    private val httpClientService: KotlinHttpClientService,
    private val corsController: KotlinCorsController
) {
    @GetMapping("/kotlin-new-rules-2")
    fun trigger(id: String): String {
        retryService.processPayment(Any())
        retryService.processPaymentSafe(Any())
        txHttpService.placeOrder(Any())
        txHttpService.placeOrderSafe(Any())
        httpClientService.fetchData(id)
        httpClientService.fetchDataSafe(id)
        corsController.getAllOrders()
        corsController.getAllOrdersTrusted()
        entityController.getEntityOrder(id)
        entityController.getOrderDto(id)
        return id
    }
}
