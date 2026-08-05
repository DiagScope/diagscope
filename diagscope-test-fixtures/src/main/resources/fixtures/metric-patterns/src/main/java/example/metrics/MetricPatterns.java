package example.metrics;

/**
 * Micrometer tag and meter-name syntax shapes used to validate receiver recognition,
 * tag key/value/type/provenance analysis, and dynamic meter names.
 */
@RestController
public class MetricPatterns {

    private static final String TAG_PROVIDER = "provider";
    private final MeterRegistry registry;
    private final NotAMeterRegistry customApi = new NotAMeterRegistry();

    public MetricPatterns(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/metrics/demo")
    public void demo(String orderId, Status status, UUID traceId) {
        boundedLiteralTag();
        boundedEnumTag(status);
        boundedConstantTag();
        unboundedParameterTag(orderId);
        unboundedUuidTag(traceId);
        registryVarargsTags(orderId);
        staticFacadeTag(orderId);
        tagsFactory(orderId);
        dynamicMeterName(orderId);
        dynamicMeterNameFromParameter(orderId);
        staticMeterName();
        unrelatedFluentApi(orderId);
    }

    void boundedLiteralTag() {
        Counter.builder("orders.processed").tag("result", "success").register(registry).increment();
    }

    void boundedEnumTag(Status status) {
        Counter.builder("orders.status").tag("status", Status.APPROVED).register(registry).increment();
    }

    void boundedConstantTag() {
        Counter.builder("orders.provider").tag(TAG_PROVIDER, TAG_PROVIDER).register(registry).increment();
    }

    void unboundedParameterTag(String orderId) {
        Counter.builder("orders.detail").tag("orderId", orderId).register(registry).increment();
    }

    void unboundedUuidTag(UUID traceId) {
        Timer.builder("orders.latency").tag("traceId", traceId).register(registry);
    }

    void registryVarargsTags(String orderId) {
        registry.counter("orders.varargs", "result", "success", "orderId", orderId).increment();
    }

    void staticFacadeTag(String orderId) {
        Metrics.counter("orders.static", "orderId", orderId).increment();
    }

    void tagsFactory(String orderId) {
        Tags.of("email", orderId);
    }

    void dynamicMeterName(String orderId) {
        registry.counter("orders." + orderId).increment();
    }

    void dynamicMeterNameFromParameter(String meterName) {
        registry.counter(meterName).increment();
    }

    void staticMeterName() {
        registry.counter("orders.static.name").increment();
    }

    void unrelatedFluentApi(String orderId) {
        customApi.tag("orderId", orderId).build();
    }
}
