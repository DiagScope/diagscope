package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.BuildSystem;
import dev.diagscope.core.domain.CallableShape;
import dev.diagscope.core.domain.Confidence;
import dev.diagscope.core.domain.Entrypoint;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.Finding;
import dev.diagscope.core.domain.Flow;
import dev.diagscope.core.domain.FlowMethod;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.ProjectLayout;
import dev.diagscope.core.domain.ProxyProfile;
import dev.diagscope.core.domain.Severity;
import dev.diagscope.core.domain.SourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Positive, negative and boundary cases for the wave-11 rules. */
class Wave11RulesTest {
    private static final Path FILE = Path.of("src/main/java/example/Service.java");

    // ── BCRYPT_CREATED_PER_CALL ───────────────────────────────────────────────

    @Test
    void bcrypt_is_reported_in_a_plain_method_but_not_in_a_bean_factory() {
        var plain = method("hash", Set.of("Service"), ctor(10, "BCryptPasswordEncoder"));
        var factory = method("encoder", Set.of("Configuration", "Bean"), ctor(20, "BCryptPasswordEncoder"));

        assertThat(new BCryptCreatedPerCallRule().evaluate(project(plain, factory)))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.severity()).isEqualTo(Severity.WARNING);
                    assertThat(finding.location().startLine()).isEqualTo(10);
                });
    }

    // ── SIMPLE_DATE_FORMAT_PER_CALL ───────────────────────────────────────────

    @Test
    void simple_date_format_is_reported_with_higher_confidence_inside_a_loop() {
        var method = method("format", Set.of(),
                ctor(10, "SimpleDateFormat", "\"yyyy\""),
                withLoop(ctor(20, "SimpleDateFormat", "\"yyyy\"")));

        assertThat(new SimpleDateFormatPerCallRule().evaluate(project(method)))
                .extracting(f -> f.location().startLine(), Finding::confidence)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(10, Confidence.MEDIUM),
                        org.assertj.core.groups.Tuple.tuple(20, Confidence.HIGH));
    }

    @Test
    void simple_date_format_in_a_post_construct_is_not_reported() {
        var method = method("init", Set.of("PostConstruct"), ctor(10, "SimpleDateFormat", "\"yyyy\""));

        assertThat(new SimpleDateFormatPerCallRule().evaluate(project(method))).isEmpty();
    }

    // ── RANDOM_PER_CALL ───────────────────────────────────────────────────────

    @Test
    void random_without_a_seed_and_secure_random_are_reported_but_a_seeded_random_is_not() {
        var method = method("draw", Set.of(),
                ctor(10, "Random"),
                ctor(11, "SecureRandom"),
                ctor(12, "Random", "42L"));

        assertThat(new RandomPerCallRule().evaluate(project(method)))
                .extracting(f -> f.location().startLine(), Finding::confidence)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(10, Confidence.MEDIUM),
                        org.assertj.core.groups.Tuple.tuple(11, Confidence.HIGH));
    }

    // ── DELETE_ALL_WITHOUT_ID ─────────────────────────────────────────────────

    @Test
    void delete_all_without_arguments_on_a_repository_is_reported() {
        var method = method("reset", Set.of(),
                call(10, "orderRepository", "OrderRepository", "deleteAll"),
                call(11, "orderRepository", "OrderRepository", "deleteAll", "orders"),
                call(12, "orderRepository", "OrderRepository", "deleteAllInBatch"),
                call(13, "cache", "Map<String, String>", "deleteAll"),
                call(14, "reactiveRepository", "ReactiveOrderRepository", "deleteAll"));

        assertThat(new DeleteAllWithoutIdRule().evaluate(project(method)))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.location().startLine()).isEqualTo(10);
                    assertThat(finding.confidence()).isEqualTo(Confidence.HIGH);
                });
    }

    // ── CACHEABLE_ON_VOID_METHOD ──────────────────────────────────────────────

    @Test
    void cacheable_on_a_void_method_is_reported_but_not_on_a_value_method_or_an_inferred_kotlin_one() {
        var voidMethod = typed("refresh", Set.of("Cacheable"), "void");
        var unitMethod = typed("reload", Set.of("Cacheable"), "Unit");
        var valueMethod = typed("find", Set.of("Cacheable"), "String");
        var inferred = typed("infer", Set.of("Cacheable"), "");
        var evict = typed("clear", Set.of("CacheEvict"), "void");

        assertThat(new CacheableOnVoidMethodRule().evaluate(project(voidMethod, unitMethod, valueMethod, inferred, evict)))
                .extracting(f -> f.evidence().get("method"))
                .containsExactlyInAnyOrder("example.Service.refresh()", "example.Service.reload()");
    }

    // ── SYSTEM_ENV_IN_BEAN ────────────────────────────────────────────────────

    @Test
    void system_env_is_reported_in_a_bean_but_not_for_jvm_constants_or_plain_classes() {
        var bean = method("config", Set.of("Service"),
                call(10, "System", "", "getenv", "\"API_URL\""),
                call(11, "System", "", "getProperty", "\"line.separator\""),
                call(12, "System", "", "getProperty", "\"feature.flag\""));
        var plain = method("util", Set.of(), call(20, "System", "", "getenv", "\"HOME\""));

        assertThat(new SystemEnvInBeanRule().evaluate(project(bean, plain)))
                .extracting(f -> f.location().startLine()).containsExactlyInAnyOrder(10, 12);
    }

    // ── SENSITIVE_HEADER_LOGGED ───────────────────────────────────────────────

    @Test
    void a_header_read_inside_a_log_argument_is_reported_but_a_mention_is_not() {
        var method = method("trace", Set.of(),
                logger(10, "info", "\"auth {}\"", "request.getHeader(\"Authorization\")"),
                logger(11, "debug", "\"cookie {}\"", "headers.get(\"cookie\")"),
                logger(12, "warn", "\"Missing Authorization header\""),
                logger(13, "info", "\"token {}\"", "request.getHeader(\"X-Request-Id\")"),
                logger(14, "info", "\"k {}\"", "headers[\"X-API-Key\"]"));

        assertThat(new SensitiveHeaderLoggedRule().evaluate(project(method)))
                .extracting(f -> f.location().startLine()).containsExactlyInAnyOrder(10, 11, 14);
    }

    // ── OPEN_REDIRECT_RISK ────────────────────────────────────────────────────

    @Test
    void redirect_to_a_dynamic_target_is_reported_in_a_rest_handler() {
        var handler = method("go", Set.of("GetMapping"),
                call(10, "response", "HttpServletResponse", "sendRedirect", "returnUrl"),
                call(11, "response", "HttpServletResponse", "sendRedirect", "\"/home\""),
                call(12, "response", "HttpServletResponse", "sendRedirect", "HOME_URL"),
                ctor(13, "RedirectView", "target"));

        assertThat(new OpenRedirectRiskRule().evaluate(project(handler)))
                .extracting(f -> f.location().startLine()).containsExactlyInAnyOrder(10, 13);
    }

    @Test
    void redirect_is_not_reported_when_the_method_validates_the_target_or_is_not_an_endpoint() {
        var validated = method("go", Set.of("GetMapping"),
                call(10, "returnUrl", "String", "startsWith", "\"/\""),
                call(11, "response", "HttpServletResponse", "sendRedirect", "returnUrl"));
        var helper = method("helper", Set.of(), call(20, "response", "HttpServletResponse", "sendRedirect", "url"));

        assertThat(new OpenRedirectRiskRule().evaluate(project(validated, helper))).isEmpty();
    }

    // ── PATH_TRAVERSAL_RISK ───────────────────────────────────────────────────

    @Test
    void file_paths_built_from_dynamic_arguments_are_reported_in_a_rest_handler() {
        var handler = method("download", Set.of("GetMapping"),
                ctor(10, "File", "name"),
                call(11, "Paths", "", "get", "BASE_DIR", "name"),
                call(12, "Path", "", "of", "\"/tmp/fixed\""),
                call(13, "Paths", "", "get", "BASE_DIR"));

        assertThat(new PathTraversalRiskRule().evaluate(project(handler)))
                .extracting(f -> f.location().startLine()).containsExactlyInAnyOrder(10, 11);
    }

    @Test
    void path_traversal_is_not_reported_when_the_method_normalises_or_is_not_an_endpoint() {
        var normalised = method("download", Set.of("GetMapping"),
                ctor(10, "File", "name"), call(11, "path", "Path", "normalize"));
        var helper = method("helper", Set.of(), ctor(20, "File", "name"));

        assertThat(new PathTraversalRiskRule().evaluate(project(normalised, helper))).isEmpty();
    }

    // ── TIMER_STARTED_NOT_STOPPED ─────────────────────────────────────────────

    @Test
    void an_unstopped_timer_sample_is_reported() {
        var leaking = method("work", Set.of(), assigned(call(10, "Timer", "", "start", "registry"), "sample"));
        var stopped = method("done", Set.of(),
                assigned(call(20, "Timer", "", "start", "registry"), "sample"),
                call(21, "sample", "Timer.Sample", "stop", "timer"));
        var returned = method("begin", Set.of(), returned(call(30, "Timer", "", "start", "registry")));

        assertThat(new TimerStartedNotStoppedRule().evaluate(project(leaking, stopped, returned)))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.location().startLine()).isEqualTo(10);
                    assertThat(finding.severity()).isEqualTo(Severity.ERROR);
                });
    }

    // ── OBJECT_INPUT_STREAM_RISK ──────────────────────────────────────────────

    @Test
    void object_input_stream_is_reported_unless_a_filter_is_set() {
        var unsafe = method("read", Set.of(), ctor(10, "ObjectInputStream", "in"));
        var filtered = method("readSafe", Set.of(), ctor(20, "ObjectInputStream", "in"),
                call(21, "ois", "ObjectInputStream", "setObjectInputFilter", "filter"));

        assertThat(new ObjectInputStreamRiskRule().evaluate(project(unsafe, filtered)))
                .singleElement().satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(10));
    }

    // ── XML_EXTERNAL_ENTITY_RISK ──────────────────────────────────────────────

    @Test
    void xml_factories_are_reported_unless_external_entities_are_disabled() {
        var unsafe = method("parse", Set.of(), call(10, "DocumentBuilderFactory", "", "newInstance"));
        var hardened = method("parseSafe", Set.of(),
                call(20, "DocumentBuilderFactory", "", "newInstance"),
                call(21, "factory", "DocumentBuilderFactory", "setFeature",
                        "\"http://apache.org/xml/features/disallow-doctype-decl\"", "true"));
        var secureProcessingOnly = method("parseHalf", Set.of(),
                call(30, "DocumentBuilderFactory", "", "newInstance"),
                call(31, "factory", "DocumentBuilderFactory", "setFeature", "XMLConstants.FEATURE_SECURE_PROCESSING", "true"));
        var stax = method("stream", Set.of(), call(40, "XMLInputFactory", "", "newFactory"));

        assertThat(new XmlExternalEntityRiskRule().evaluate(project(unsafe, hardened, secureProcessingOnly, stax)))
                .extracting(f -> f.location().startLine(), Finding::confidence)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(10, Confidence.HIGH),
                        org.assertj.core.groups.Tuple.tuple(30, Confidence.MEDIUM),
                        org.assertj.core.groups.Tuple.tuple(40, Confidence.HIGH));
    }

    // ── TRANSACTIONAL_NO_ROLLBACK_FOR_EXCEPTION ───────────────────────────────

    @Test
    void no_rollback_for_a_broad_exception_is_reported() {
        var broad = withAttributes("save", Set.of("Transactional"), Map.of("Transactional", Map.of("noRollbackFor", "Exception.class")));
        var kotlin = withAttributes("saveKt", Set.of("Transactional"), Map.of("Transactional", Map.of("noRollbackFor", "[Throwable::class]")));
        var narrow = withAttributes("saveSafe", Set.of("Transactional"),
                Map.of("Transactional", Map.of("noRollbackFor", "OrderAlreadyExistsException.class")));
        var other = withAttributes("saveOther", Set.of("Transactional"), Map.of("Transactional", Map.of("readOnly", "true")));
        var jakarta = withAttributes("saveJakarta", Set.of("Transactional"),
                Map.of("Transactional", Map.of("dontRollbackOn", "{java.lang.Exception.class}")));

        assertThat(new TransactionalNoRollbackForExceptionRule().evaluate(project(broad, kotlin, narrow, other, jakarta)))
                .extracting(f -> f.evidence().get("exception")).containsExactlyInAnyOrder("Exception", "Throwable", "Exception");
    }

    @Test
    void a_class_level_annotation_is_reported_once_per_type() {
        var first = withAttributes("a", Set.of("Transactional"), Map.of("Transactional", Map.of("noRollbackFor", "Exception.class")));
        var second = withAttributes("b", Set.of("Transactional"), Map.of("Transactional", Map.of("noRollbackFor", "Exception.class")));

        assertThat(new TransactionalNoRollbackForExceptionRule().evaluate(project(first, second))).hasSize(1);
    }

    // ── SEMAPHORE_RELEASE_NOT_IN_FINALLY ──────────────────────────────────────

    @Test
    void a_semaphore_released_outside_finally_is_reported() {
        var unsafe = method("limited", Set.of(),
                call(10, "permits", "Semaphore", "acquire"),
                call(12, "permits", "Semaphore", "release"));
        var safe = method("limitedSafe", Set.of(),
                call(20, "permits", "Semaphore", "acquire"),
                inFinally(call(22, "permits", "Semaphore", "release")));
        var leaking = method("leaky", Set.of(), call(30, "permits", "Semaphore", "acquire"));

        assertThat(new SemaphoreReleaseNotInFinallyRule().evaluate(project(unsafe, safe, leaking)))
                .extracting(f -> f.location().startLine(), Finding::confidence)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(10, Confidence.HIGH),
                        org.assertj.core.groups.Tuple.tuple(30, Confidence.MEDIUM));
    }

    // ── COUNT_DOWN_LATCH_NOT_IN_FINALLY ───────────────────────────────────────

    @Test
    void count_down_after_other_work_outside_finally_is_reported() {
        var unsafe = method("task", Set.of(),
                call(10, "worker", "Worker", "process"),
                call(11, "latch", "CountDownLatch", "countDown"));
        var safe = method("taskSafe", Set.of(),
                call(20, "worker", "Worker", "process"),
                inFinally(call(22, "latch", "CountDownLatch", "countDown")));
        var callback = method("onDone", Set.of(), call(30, "latch", "CountDownLatch", "countDown"));

        assertThat(new CountDownLatchNotInFinallyRule().evaluate(project(unsafe, safe, callback)))
                .singleElement().satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(11));
    }

    // ── VIRTUAL_THREAD_WITH_THREAD_LOCAL ──────────────────────────────────────

    @Test
    void thread_local_set_on_a_virtual_thread_is_reported() {
        var virtual = method("handle", Set.of("RunOnVirtualThread"),
                call(10, "holder", "ThreadLocal<String>", "set", "id"),
                call(11, "map", "Map<String, String>", "set", "id"));
        var platform = method("legacy", Set.of(), call(20, "holder", "ThreadLocal<String>", "set", "id"));

        assertThat(new VirtualThreadWithThreadLocalRule().evaluate(project(virtual, platform)))
                .singleElement().satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(10));
    }

    // ── REFLECTIVE_ACCESS_IN_PRODUCTION ───────────────────────────────────────

    @Test
    void set_accessible_true_is_reported_outside_tests() {
        var production = method("inject", Set.of(),
                call(10, "field", "Field", "setAccessible", "true"),
                call(11, "field", "Field", "setAccessible", "false"));
        var test = method("prepare", Set.of("BeforeEach"), call(20, "field", "Field", "setAccessible", "true"));

        assertThat(new ReflectiveAccessInProductionRule().evaluate(project(production, test)))
                .singleElement().satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(10));
    }

    // ── EAGER_COLLECTION_FETCH / LAZY_BEAN_CIRCULAR_INDICATOR ─────────────────

    @Test
    void eager_collections_are_reported_once_per_entity_with_the_field_names() {
        var first = withAttributes("getItems", Set.of("Entity", "EagerCollectionFetch"),
                Map.of("EagerCollectionFetch", Map.of("fields", "items,tags")));
        var second = withAttributes("getName", Set.of("Entity", "EagerCollectionFetch"),
                Map.of("EagerCollectionFetch", Map.of("fields", "items,tags")));
        var lazy = typed("getOther", Set.of("Entity"), "String");

        assertThat(new EagerCollectionFetchRule().evaluate(project(first, second, lazy)))
                .singleElement().satisfies(finding -> assertThat(finding.evidence()).containsEntry("fields", "items,tags"));
    }

    @Test
    void lazy_constructor_parameters_are_reported_on_spring_beans_only() {
        var bean = typed("find", Set.of("Service", "LazyConstructorParameter"), "String");
        var plain = typed("find2", Set.of("LazyConstructorParameter"), "String");

        assertThat(new LazyBeanCircularIndicatorRule().evaluate(project(bean, plain)))
                .singleElement().satisfies(finding -> assertThat(finding.severity()).isEqualTo(Severity.INFO));
    }

    // ── JPA_ENTITY_CONSTRUCTOR_IN_LOOP ────────────────────────────────────────

    @Test
    void an_entity_created_and_saved_in_a_loop_is_reported() {
        var entity = new MethodModel(new MethodId("example.Order", "getId", List.of()),
                new SourceLocation(FILE, 1, 1), Set.of("Entity"), List.of(), List.of(), List.of(), List.of(), List.of());
        var importer = method("importAll", Set.of(),
                withLoop(ctor(10, "Order")),
                withLoop(call(11, "orderRepository", "OrderRepository", "save", "order")));
        var oneOff = method("importOne", Set.of(), ctor(20, "Order"), call(21, "orderRepository", "OrderRepository", "save", "o"));
        var notEntity = method("importDtos", Set.of(),
                withLoop(ctor(30, "OrderDto")),
                withLoop(call(31, "orderRepository", "OrderRepository", "save", "o")));

        assertThat(new JpaEntityConstructorInLoopRule().evaluate(project(entity, importer, oneOff, notEntity)))
                .singleElement().satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(10));
    }

    // ── CACHE_EVICT_WITHOUT_ALL_ENTRIES_ON_VOID ───────────────────────────────

    @Test
    void parameterless_cache_evict_without_all_entries_is_reported() {
        var bare = typed("clear", Set.of("CacheEvict"), "void");
        var all = withAttributes("clearAll", Set.of("CacheEvict"), Map.of("CacheEvict", Map.of("allEntries", "true")));
        var keyed = withAttributes("clearKey", Set.of("CacheEvict"), Map.of("CacheEvict", Map.of("key", "'all'")));
        var withParam = new MethodModel(new MethodId("example.Service", "evict", List.of("String")),
                new SourceLocation(FILE, 1, 1), Set.of("CacheEvict"), List.of(), List.of(), List.of(), List.of(), List.of());

        assertThat(new CacheEvictWithoutAllEntriesOnVoidRule().evaluate(project(bare, all, keyed, withParam)))
                .singleElement().satisfies(finding -> assertThat(finding.evidence())
                        .containsEntry("method", "example.Service.clear()"));
    }

    // ── MISSING_BULKHEAD_ON_HTTP_CLIENT ───────────────────────────────────────

    @Test
    void an_http_call_with_no_isolation_on_the_path_is_reported() {
        var endpoint = method("endpoint", Set.of("GetMapping"));
        var client = method("fetch", Set.of(), call(10, "rest", "RestTemplate", "getForObject", "url"));

        assertThat(new MissingBulkheadOnHttpClientRule().evaluate(flow(endpoint, client)))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.severity()).isEqualTo(Severity.INFO);
                    assertThat(finding.relatedFlows().get(0).path()).hasSize(2);
                });
    }

    @Test
    void an_http_call_is_not_reported_when_any_method_on_the_path_isolates_it() {
        var endpoint = method("endpoint", Set.of("GetMapping", "Bulkhead"));
        var client = method("fetch", Set.of(), call(10, "rest", "RestTemplate", "getForObject", "url"));
        var breaker = method("fetchBreaker", Set.of("CircuitBreaker"), call(20, "rest", "RestTemplate", "getForObject", "url"));

        assertThat(new MissingBulkheadOnHttpClientRule().evaluate(flow(endpoint, client))).isEmpty();
        assertThat(new MissingBulkheadOnHttpClientRule().evaluate(flow(method("e2", Set.of("GetMapping")), breaker))).isEmpty();
    }

    // ── HTTP_CLIENT_NO_ERROR_HANDLER ──────────────────────────────────────────

    @Test
    void a_rest_template_without_an_error_handler_is_reported() {
        var unset = method("client", Set.of(), ctor(10, "RestTemplate"));
        var configured = method("clientSafe", Set.of(), ctor(20, "RestTemplate"),
                call(21, "template", "RestTemplate", "setErrorHandler", "handler"));

        assertThat(new HttpClientNoErrorHandlerRule().evaluate(project(unset, configured)))
                .singleElement().satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(10));
    }

    // ── KAFKA_LISTENER_MANUAL_ACK_WRONG_MODE ──────────────────────────────────

    @Test
    void an_acknowledgment_parameter_without_a_container_factory_is_reported_with_low_confidence() {
        var listener = listener("onMessage", List.of("String", "org.springframework.kafka.support.Acknowledgment"), Map.of());
        var configured = listener("onMessage2", List.of("String", "Acknowledgment"),
                Map.of("KafkaListener", Map.of("containerFactory", "manualAckFactory")));
        var noAck = listener("onMessage3", List.of("String"), Map.of());

        assertThat(new KafkaListenerManualAckWrongModeRule().evaluate(project(listener, configured, noAck)))
                .singleElement().satisfies(finding -> {
                    assertThat(finding.confidence()).isEqualTo(Confidence.LOW);
                    assertThat(finding.evidence()).containsEntry("method",
                            "example.Service.onMessage(String,org.springframework.kafka.support.Acknowledgment)");
                });
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static MethodModel method(String name, Set<String> annotations, InvocationEvidence... invocations) {
        return new MethodModel(new MethodId("example.Service", name, List.of()), new SourceLocation(FILE, 1, 1),
                annotations, List.of(), Arrays.asList(invocations), List.of(), List.of(), List.of(),
                ProxyProfile.unknown(), Map.of(), CallableShape.fixed(0), "");
    }

    private static MethodModel typed(String name, Set<String> annotations, String returnType) {
        return new MethodModel(new MethodId("example.Service", name, List.of()), new SourceLocation(FILE, 1, 1),
                annotations, List.of(), List.of(), List.of(), List.of(), List.of(),
                ProxyProfile.unknown(), Map.of(), CallableShape.fixed(0), returnType);
    }

    private static MethodModel withAttributes(
            String name, Set<String> annotations, Map<String, Map<String, String>> attributes) {
        return new MethodModel(new MethodId("example.Service", name, List.of()), new SourceLocation(FILE, 1, 1),
                annotations, List.of(), List.of(), List.of(), List.of(), List.of(),
                ProxyProfile.unknown(), attributes, CallableShape.fixed(0), "void");
    }

    private static MethodModel listener(
            String name, List<String> parameters, Map<String, Map<String, String>> attributes) {
        return new MethodModel(new MethodId("example.Service", name, parameters), new SourceLocation(FILE, 1, 1),
                Set.of("KafkaListener"), List.of(), List.of(), List.of(), List.of(), List.of(),
                ProxyProfile.unknown(), attributes, CallableShape.fixed(parameters.size()), "void");
    }

    private static InvocationEvidence ctor(int line, String type, String... arguments) {
        return call(line, "", "", type, arguments);
    }

    private static InvocationEvidence call(int line, String scope, String receiverType, String name, String... arguments) {
        return new InvocationEvidence(new SourceLocation(FILE, line, line), scope, receiverType, name,
                Arrays.asList(arguments), InvocationResultUsage.UNKNOWN);
    }

    private static InvocationEvidence logger(int line, String level, String... arguments) {
        return new InvocationEvidence(new SourceLocation(FILE, line, line), "log", "Logger", level,
                Arrays.asList(arguments), InvocationResultUsage.IGNORED, false, false, "", false, false, true, List.of());
    }

    private static InvocationEvidence assigned(InvocationEvidence call, String variable) {
        return new InvocationEvidence(call.location(), call.scope(), call.receiverType(), call.methodName(),
                call.arguments(), InvocationResultUsage.ASSIGNED, false, false, variable, false, false, false, List.of());
    }

    private static InvocationEvidence returned(InvocationEvidence call) {
        return new InvocationEvidence(call.location(), call.scope(), call.receiverType(), call.methodName(),
                call.arguments(), InvocationResultUsage.RETURNED);
    }

    private static InvocationEvidence inFinally(InvocationEvidence call) {
        return new InvocationEvidence(call.location(), call.scope(), call.receiverType(), call.methodName(),
                call.arguments(), call.resultUsage(), false, false, "", true, false, false, List.of());
    }

    private static InvocationEvidence withLoop(InvocationEvidence call) {
        return new InvocationEvidence(call.location(), call.scope(), call.receiverType(), call.methodName(),
                call.arguments(), call.resultUsage(), false, false, "", false, true, false, List.of());
    }

    private static AnalyzedProject project(MethodModel... methods) {
        var byId = new LinkedHashMap<MethodId, MethodModel>();
        for (MethodModel method : methods) byId.put(method.id(), method);
        var root = Path.of(".");
        return new AnalyzedProject("test", root,
                new ProjectLayout(BuildSystem.MAVEN, root, List.of(), List.of(root)), byId, List.of(), 1L, List.of());
    }

    private static Flow flow(MethodModel... chain) {
        var reached = new ArrayList<FlowMethod>();
        var path = new ArrayList<MethodId>();
        for (MethodModel method : chain) {
            path.add(method.id());
            reached.add(new FlowMethod(method, path.size() - 1, Confidence.HIGH, List.copyOf(path)));
        }
        return new Flow(new Entrypoint(EntrypointType.REST, chain[0].id(), "GET /x", chain[0].location()), reached, List.of());
    }
}
