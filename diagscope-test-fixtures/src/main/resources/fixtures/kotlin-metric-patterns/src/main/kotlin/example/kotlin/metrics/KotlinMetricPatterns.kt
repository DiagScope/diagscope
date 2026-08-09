package example.kotlin.metrics

@RestController
class KotlinMetricPatterns(
    private val registry: MeterRegistry,
    private val customApi: NotAMeterRegistry
) {
    private val TAG_PROVIDER: String = "provider"

    @GetMapping("/kotlin/metrics")
    fun demo(orderId: String, status: Status, traceId: UUID, ids: List<String>) {
        boundedLiteralTag()
        boundedEnumTag(status)
        boundedConstantTag()
        unboundedParameterTag(orderId)
        unboundedUuidTag(traceId)
        registryVarargsTags(orderId)
        staticFacadeTag(orderId)
        tagsFactory(orderId)
        dynamicMeterName(orderId)
        dynamicMeterNameFromParameter(orderId)
        staticMeterName()
        metricInsideLoop(ids)
        unrelatedFluentApi(orderId)
    }

    fun boundedLiteralTag() {
        Counter.builder("orders.processed").tag("result", "success").register(registry).increment()
    }

    fun boundedEnumTag(status: Status) {
        Counter.builder("orders.status").tag("status", Status.APPROVED).register(registry).increment()
    }

    fun boundedConstantTag() {
        Counter.builder("orders.provider").tag(TAG_PROVIDER, TAG_PROVIDER).register(registry).increment()
    }

    fun unboundedParameterTag(orderId: String) {
        Counter.builder("orders.detail").tag("orderId", orderId).register(registry).increment()
    }

    fun unboundedUuidTag(traceId: UUID) {
        Timer.builder("orders.latency").tag("traceId", traceId).register(registry)
    }

    fun registryVarargsTags(orderId: String) {
        registry.counter("orders.varargs", "result", "success", "orderId", orderId).increment()
    }

    fun staticFacadeTag(orderId: String) {
        Metrics.counter("orders.static", "orderId", orderId).increment()
    }

    fun tagsFactory(orderId: String) {
        Tags.of("email", orderId)
    }

    fun dynamicMeterName(orderId: String) {
        registry.counter("orders.$orderId").increment()
    }

    fun dynamicMeterNameFromParameter(meterName: String) {
        registry.counter(meterName).increment()
    }

    fun staticMeterName() {
        registry.counter("orders.static.name").increment()
    }

    fun metricInsideLoop(ids: List<String>) {
        for (id in ids) {
            registry.counter("orders.loop", "result", "success").increment()
        }
    }

    fun unrelatedFluentApi(orderId: String) {
        customApi.tag("orderId", orderId).build()
    }
}

@Aspect
@Component
class KotlinMetricsAspect {
    @Around("execution(* example.kotlin.metrics.KotlinMetricPatterns.dynamic*(..))")
    fun observe() {}
}
