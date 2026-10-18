package dev.diagscope.core.application.rule;

import dev.diagscope.core.domain.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression tests for false positives found by scanning open-source Spring projects. */
class CalibrationRulesTest {

    // ── MISSING_REQUEST_BODY_VALIDATION ──────────────────────────────────────

    @Test
    void requestBodyWithoutValid_isReported() {
        var endpoint = controllerPost("create", List.of("OrderPayload"), Map.of("p0", "RequestBody"));
        assertThat(new MissingRequestBodyValidationRule().evaluate(project(endpoint))).hasSize(1);
    }

    @Test
    void requestBodyWithValid_isNotReported_evenWithoutClassLevelValidated() {
        var endpoint = controllerPost("create", List.of("OrderPayload"), Map.of("p0", "Valid,RequestBody"));
        assertThat(new MissingRequestBodyValidationRule().evaluate(project(endpoint))).isEmpty();
    }

    @Test
    void requestBodyWithValidatedParameter_isNotReported() {
        var endpoint = controllerPost("create", List.of("OrderPayload"), Map.of("p0", "Validated,RequestBody"));
        assertThat(new MissingRequestBodyValidationRule().evaluate(project(endpoint))).isEmpty();
    }

    @Test
    void classLevelValidated_doesNotValidateTheBody() {
        var endpoint = controllerPost("create", List.of("OrderPayload"), Map.of("p0", "RequestBody"),
                "RestController", "PostMapping", "Validated");
        assertThat(new MissingRequestBodyValidationRule().evaluate(project(endpoint))).hasSize(1);
    }

    @Test
    void plainStringBodyAndParametersWithoutBody_areNotReported() {
        assertThat(new MissingRequestBodyValidationRule().evaluate(project(
                controllerPost("raw", List.of("String"), Map.of("p0", "RequestBody")),
                controllerPost("byId", List.of("Long"), Map.of("p0", "PathVariable")),
                controllerPost("bare", List.of("OrderPayload"), Map.of())))).isEmpty();
    }

    // ── MISSING_TRANSACTION_ANNOTATION ───────────────────────────────────────

    @Test
    void singleRepositorySave_isNotReported() {
        var method = service("saveOne", Set.of(), save(10, "repository"));
        assertThat(new MissingTransactionAnnotationRule().evaluate(flow(method))).isEmpty();
    }

    @Test
    void twoRepositoryWrites_areReported() {
        var method = service("saveTwo", Set.of(), save(10, "ownerRepository"), save(11, "petRepository"));
        assertThat(new MissingTransactionAnnotationRule().evaluate(flow(method))).hasSize(1);
    }

    @Test
    void entityManagerWrite_isReportedEvenOnce() {
        var method = service("persistOne", Set.of(),
                invocation(10, "entityManager", "EntityManager", "persist"));
        assertThat(new MissingTransactionAnnotationRule().evaluate(flow(method))).hasSize(1);
    }

    @Test
    void writesUnderATransactionalCaller_areNotReported() {
        var callee = service("saveTwo", Set.of(), save(10, "ownerRepository"), save(11, "petRepository"));
        var caller = new MethodModel(new MethodId("example.Facade", "run", List.of()), location(1),
                Set.of("Transactional"), List.of(), List.of(), List.of(), List.of(), List.of());
        var entrypoint = new Entrypoint(EntrypointType.REST, caller.id(), "POST /run", caller.location());
        var flow = new Flow(entrypoint, List.of(
                new FlowMethod(caller, 0, Confidence.HIGH, List.of(caller.id())),
                new FlowMethod(callee, 1, Confidence.HIGH, List.of(caller.id(), callee.id()))), List.of());
        assertThat(new MissingTransactionAnnotationRule().evaluate(flow)).isEmpty();
    }

    @Test
    void receiverNamesContainingEm_areNotTreatedAsEntityManagers() {
        var method = service("dropItem", Set.of(), invocation(10, "itemList", "List", "remove"));
        assertThat(new MissingTransactionAnnotationRule().evaluate(flow(method))).isEmpty();
    }

    // ── TRANSACTIONAL_ON_INTERFACE ───────────────────────────────────────────

    @Test
    void transactionalOnSpringDataRepositoryInterface_isNotReported() {
        var method = interfaceMethod(Set.of("Transactional", "Repository"));
        assertThat(new TransactionalOnInterfaceRule().evaluate(project(method))).isEmpty();
    }

    // ── ENTITY_EXPOSED_IN_REST_RESPONSE ──────────────────────────────────────

    @Test
    void modelAttributeOnAViewController_isNotAnExposedEntity() {
        var entity = method("example.Owner", "getId", Set.of("Entity"), "Long");
        var attribute = method("example.OwnerController", "findOwner",
                Set.of("Controller", "RequestMapping", "ModelAttribute"), "Owner");
        assertThat(new EntityExposedInRestResponseRule().evaluate(project(entity, attribute))).isEmpty();
    }

    @Test
    void entityReturnedFromARestController_isReported() {
        var entity = method("example.Owner", "getId", Set.of("Entity"), "Long");
        var endpoint = method("example.OwnerResource", "get", Set.of("RestController", "GetMapping"), "Owner");
        assertThat(new EntityExposedInRestResponseRule().evaluate(project(entity, endpoint))).hasSize(1);
    }

    // ── MISSING_PAGINATION ───────────────────────────────────────────────────

    @Test
    void filteredRepositoryQuery_isNotReported_butFindAllIs() {
        var filtered = repositoryMethod("findByLastName", "List<Owner>", List.of("String"));
        var all = repositoryMethod("findAll", "List<Owner>", List.of());
        assertThat(new MissingPaginationRule().evaluate(project(filtered, all)))
                .singleElement().satisfies(f -> assertThat(f.evidence()).containsEntry("method",
                        "example.OwnerRepository.findAll()"));
    }

    // ── CLOCK_DEPENDENCY_NOT_INJECTED / HTTP_CLIENT_CREATED_PER_REQUEST / APPLICATION_CONTEXT_LOCATOR ──

    @Test
    void nowWithAZone_isStillTheSystemClock_butNowWithAClockIsNot() {
        var zoned = service("a", Set.of("Service"), nowCall("ZoneId.of(\"UTC\")"));
        var utc = service("b", Set.of("Service"), nowCall("ZoneOffset.UTC"));
        var injected = service("c", Set.of("Service"), nowCall("clock"));
        assertThat(new ClockDependencyNotInjectedRule().evaluate(project(zoned, utc))).hasSize(2);
        assertThat(new ClockDependencyNotInjectedRule().evaluate(project(injected))).isEmpty();
    }

    @Test
    void restTemplateAndWebClientPerCall_areNotReported_butOkHttpClientIs() {
        var rest = service("a", Set.of("Service"), construct("RestTemplate"));
        var web = service("b", Set.of("Service"), construct("WebClient"));
        var okHttp = service("c", Set.of("Service"), construct("OkHttpClient"));
        assertThat(new HttpClientCreatedPerRequestRule().evaluate(project(rest, web))).isEmpty();
        assertThat(new HttpClientCreatedPerRequestRule().evaluate(project(okHttp))).hasSize(1);
    }

    @Test
    void getBeanIsReportedOnAContextOnly() {
        var onContext = service("a", Set.of("Service"), receiverCall("applicationContext", "ApplicationContext", "getBean"));
        var onOther = service("b", Set.of("Service"), receiverCall("registry", "ServiceRegistry", "getBean"));
        assertThat(new ApplicationContextLocatorRule().evaluate(project(onContext))).hasSize(1);
        assertThat(new ApplicationContextLocatorRule().evaluate(project(onOther))).isEmpty();
        var bootstrap = new MethodModel(new MethodId("example.Application", "main", List.of("String[]")), location(1),
                Set.of("Component"), List.of(),
                List.of(receiverCall("context", "ConfigurableApplicationContext", "getBean")), List.of(), List.of(),
                List.of());
        assertThat(new ApplicationContextLocatorRule().evaluate(project(bootstrap))).isEmpty();
    }

    private static InvocationEvidence nowCall(String argument) {
        return new InvocationEvidence(location(12), "LocalDateTime", "LocalDateTime", "now", List.of(argument),
                InvocationResultUsage.ASSIGNED);
    }

    private static InvocationEvidence construct(String type) {
        return new InvocationEvidence(location(12), "", type, type, List.of(), InvocationResultUsage.ASSIGNED);
    }

    private static InvocationEvidence receiverCall(String scope, String type, String name) {
        return new InvocationEvidence(location(12), scope, type, name, List.of(), InvocationResultUsage.ASSIGNED);
    }

    @Test
    void aParameterWithTwoAnnotationsOfTheSameSimpleName_doesNotCrash() {
        // Spring's @RequestBody next to Swagger's @RequestBody both have the simple name RequestBody.
        var endpoint = controllerPost("create", List.of("Owner"), Map.of("p0", "RequestBody,RequestBody,Valid"));
        assertThat(endpoint.parameterAnnotations(0)).containsExactlyInAnyOrder("RequestBody", "Valid");
    }

    // ── MASS_ASSIGNMENT_RISK ─────────────────────────────────────────────────

    @Test
    void controllerWithInitBinder_isNotReported() {
        var entity = method("example.Owner", "getId", Set.of("Entity"), "Long");
        var endpoint = controllerPost("create", List.of("Owner"), Map.of("p0", "RequestBody"));
        var binder = method("example.OrderController", "setAllowedFields", Set.of("InitBinder"), "void");
        assertThat(new MassAssignmentRiskRule().evaluate(project(entity, endpoint))).hasSize(1);
        // The binder fills form and @ModelAttribute parameters; a @RequestBody is read by Jackson and not guarded by it.
        assertThat(new MassAssignmentRiskRule().evaluate(project(entity, endpoint, binder))).hasSize(1);
        var formEndpoint = controllerPost("submit", List.of("Owner"), Map.of());
        assertThat(new MassAssignmentRiskRule().evaluate(project(entity, formEndpoint))).hasSize(1);
        assertThat(new MassAssignmentRiskRule().evaluate(project(entity, formEndpoint, binder))).isEmpty();
    }

    @Test
    void entityPassedAsPathVariable_isNotReported() {
        var entity = method("example.Owner", "getId", Set.of("Entity"), "Long");
        var endpoint = controllerPost("update", List.of("Owner"), Map.of("p0", "PathVariable"));
        assertThat(new MassAssignmentRiskRule().evaluate(project(entity, endpoint))).isEmpty();
    }

    // ── Jakarta / CDI ────────────────────────────────────────────────────────

    @Test
    void jakartaTransactionalHasNoReadOnlyAttribute() {
        var spring = method("example.FruitResource", "getAll", Set.of("Transactional"), "List");
        var jakarta = method("example.FruitResource", "getSingle",
                Set.of("Transactional", MethodModel.JAKARTA_TRANSACTIONAL_ANNOTATION), "Fruit");
        assertThat(new TransactionalReadOnlyMissingRule().evaluate(project(spring))).hasSize(1);
        assertThat(new TransactionalReadOnlyMissingRule().evaluate(project(jakarta))).isEmpty();
    }

    @Test
    void cdiAndJaxRsClassesAreContainerManaged() {
        var proxy = new ProxyProfile(MethodVisibility.PUBLIC, false, false, false, false, false,
                Set.of("Transactional"), List.of());
        var bare = managedMethod(Set.of("Transactional"), proxy);
        var resource = managedMethod(Set.of("Transactional", "Path"), proxy);
        var scoped = managedMethod(Set.of("Transactional", "ApplicationScoped"), proxy);
        assertThat(new UnmanagedAdviceTargetRule().evaluate(flow(bare))).hasSize(1);
        assertThat(new UnmanagedAdviceTargetRule().evaluate(flow(resource))).isEmpty();
        assertThat(new UnmanagedAdviceTargetRule().evaluate(flow(scoped))).isEmpty();
    }

    // ── OPTIONAL_OR_ELSE_NULL ────────────────────────────────────────────────

    @Test
    void orElseNull_isIgnoredWhenTheMethodChecksTheVariableForNull() {
        var orElseNull = new InvocationEvidence(location(10), "parent", "Optional", "orElse", List.of("null"),
                InvocationResultUsage.ASSIGNED, false, false, "current", false, false, false, List.of());
        var unchecked = new MethodModel(new MethodId("example.Walker", "walk", List.of()), location(1), Set.of(),
                List.of(), List.of(orElseNull), List.of(), List.of(), List.of());
        var checked = new MethodModel(new MethodId("example.Walker", "walkChecked", List.of()), location(1), Set.of(),
                List.of(), List.of(orElseNull), List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of(MethodModel.NULL_CHECKED_ANNOTATION, Map.of("names", "current")), CallableShape.fixed(0),
                "void", false);
        assertThat(new OptionalOrElseNullRule().evaluate(flow(unchecked))).hasSize(1);
        assertThat(new OptionalOrElseNullRule().evaluate(flow(checked))).isEmpty();
    }

    // ── EVENT_LISTENER_ON_PRIVATE_METHOD ─────────────────────────────────────

    @Test
    void privateEventListener_isReportedOnlyOnAProxiedBean() {
        var proxied = new ProxyProfile(MethodVisibility.PRIVATE, false, false, false, true, false,
                Set.of("Transactional"), List.of());
        var plain = new ProxyProfile(MethodVisibility.PRIVATE, false, false, false, true, false, Set.of(), List.of());
        var isStatic = new ProxyProfile(MethodVisibility.PRIVATE, true, false, false, true, false,
                Set.of("Transactional"), List.of());
        var publicListener = new ProxyProfile(MethodVisibility.PUBLIC, false, false, false, true, false,
                Set.of("Transactional"), List.of());

        var rule = new EventListenerOnPrivateMethodRule();
        assertThat(rule.evaluate(project(listener("onProxied", proxied)))).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.ERROR);
            assertThat(f.confidence()).isEqualTo(Confidence.MEDIUM);
            assertThat(f.message()).contains("start-up").doesNotContain("silently");
        });
        assertThat(rule.evaluate(project(listener("onPlain", plain)))).isEmpty();
        assertThat(rule.evaluate(project(listener("onStatic", isStatic)))).isEmpty();
        assertThat(rule.evaluate(project(listener("onPublic", publicListener)))).isEmpty();
    }

    // ── framework-claim audit of the WARNING rules ───────────────────────────

    @Test
    void problemDetailHandlers_carryTheirOwnStatus_butACustomErrorResponseDoesNot() {
        var problem = exceptionHandler("handleProblem", "ProblemDetail");
        var custom = exceptionHandler("handleCustom", "ErrorResponse");
        assertThat(new MissingResponseStatusRule().evaluate(project(problem))).isEmpty();
        assertThat(new MissingResponseStatusRule().evaluate(project(custom))).hasSize(1);
    }

    @Test
    void reactiveScheduledMethods_areSubscribedNotDiscarded() {
        var mono = scheduled("pollMono", "Mono<Void>");
        var flux = scheduled("pollFlux", "Flux<String>");
        var value = scheduled("pollValue", "String");
        var rule = new ScheduledNonVoidReturnRule();
        assertThat(rule.evaluate(project(mono, flux))).isEmpty();
        assertThat(rule.evaluate(project(value))).hasSize(1);
    }

    @Test
    void entityManagerClear_ignoresReceiversThatMerelyContainEm() {
        var clear = new InvocationEvidence(location(10), "itemList", "List", "clear", List.of(),
                InvocationResultUsage.IGNORED);
        var method = new MethodModel(new MethodId("example.Service", "reset", List.of()), location(1),
                Set.of("Transactional"), List.of(), List.of(clear), List.of(), List.of(), List.of());
        assertThat(new EntityManagerClearInTransactionRule().evaluate(project(method))).isEmpty();
    }

    @Test
    void dataSourceUtilsConnection_isTheTransactionAwareWay_notAnEscape() {
        var utils = new InvocationEvidence(location(10), "DataSourceUtils", "DataSourceUtils", "getConnection",
                List.of("dataSource"), InvocationResultUsage.ASSIGNED);
        var template = new InvocationEvidence(location(11), "jdbcTemplate.getDataSource()", "DataSource",
                "getConnection", List.of(), InvocationResultUsage.ASSIGNED);
        var viaUtils = new MethodModel(new MethodId("example.Repo", "a", List.of()), location(1), Set.of(),
                List.of(), List.of(utils), List.of(), List.of(), List.of());
        var viaTemplate = new MethodModel(new MethodId("example.Repo", "b", List.of()), location(1), Set.of(),
                List.of(), List.of(template), List.of(), List.of(), List.of());
        assertThat(new JdbcTemplateConnectionEscapeRule().evaluate(flow(viaUtils))).isEmpty();
        assertThat(new JdbcTemplateConnectionEscapeRule().evaluate(flow(viaTemplate))).hasSize(1);
    }

    // ── second audit pass ────────────────────────────────────────────────────

    @Test
    void joinInLoop_isReportedOnlyWhenTheTaskIsStartedInTheSameIteration() {
        var chained = new InvocationEvidence(location(10), "CompletableFuture.supplyAsync(() -> work())", "",
                "join", List.of(), InvocationResultUsage.USED_AS_ARGUMENT, false, false, "", false, true, false,
                List.of());
        var started = new InvocationEvidence(location(20), "CompletableFuture", "CompletableFuture", "supplyAsync",
                List.of("() -> work()"), InvocationResultUsage.ASSIGNED, false, false, "future", false, true, false,
                List.of());
        var joinSame = new InvocationEvidence(location(21), "future", "CompletableFuture", "join", List.of(),
                InvocationResultUsage.USED_AS_ARGUMENT, false, false, "", false, true, false, List.of());
        var joinEarlierFutures = new InvocationEvidence(location(40), "future", "CompletableFuture", "join",
                List.of(), InvocationResultUsage.USED_AS_ARGUMENT, false, false, "", false, true, false, List.of());
        var rule = new SequentialFutureJoinInLoopRule();
        assertThat(rule.evaluate(flow(withInvocations(chained)))).hasSize(1);
        assertThat(rule.evaluate(flow(withInvocations(started, joinSame)))).hasSize(1);
        // The two-phase pattern: the futures were started before the loop, so joining them in it is correct.
        assertThat(rule.evaluate(flow(withInvocations(joinEarlierFutures)))).isEmpty();
    }

    @Test
    void filesStreamsNeedClosing_butBufferedReaderLinesDoNot() {
        var files = new InvocationEvidence(location(10), "Files", "Files", "lines", List.of("path"),
                InvocationResultUsage.ASSIGNED);
        var reader = new InvocationEvidence(location(11), "reader", "BufferedReader", "lines", List.of(),
                InvocationResultUsage.ASSIGNED);
        var profiles = new InvocationEvidence(location(12), "profiles", "ProfileRepo", "list", List.of(),
                InvocationResultUsage.ASSIGNED);
        var rule = new StreamIoNotClosedRule();
        assertThat(rule.evaluate(flow(withInvocations(files)))).hasSize(1);
        assertThat(rule.evaluate(flow(withInvocations(reader, profiles)))).isEmpty();
    }

    @Test
    void threadLocalSetInOneMethodAndRemovedInAnotherOfTheSameClass_isNotReported() {
        var set = new InvocationEvidence(location(10), "requestId", "ThreadLocal", "set", List.of("id"),
                InvocationResultUsage.IGNORED);
        var remove = new InvocationEvidence(location(20), "requestId", "ThreadLocal", "remove", List.of(),
                InvocationResultUsage.IGNORED);
        var preHandle = new MethodModel(new MethodId("example.Interceptor", "preHandle", List.of()), location(1),
                Set.of(), List.of(), List.of(set), List.of(), List.of(), List.of());
        var afterCompletion = new MethodModel(new MethodId("example.Interceptor", "afterCompletion", List.of()),
                location(15), Set.of(), List.of(), List.of(remove), List.of(), List.of(), List.of());
        var leaky = new MethodModel(new MethodId("example.Other", "run", List.of()), location(1), Set.of(),
                List.of(), List.of(set), List.of(), List.of(), List.of());
        var rule = new ThreadLocalNotRemovedRule();
        assertThat(rule.evaluate(project(preHandle, afterCompletion))).isEmpty();
        assertThat(rule.evaluate(project(leaky))).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(Severity.WARNING));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    // ── HTTP_CLIENT_ERROR_DISCARDED ──────────────────────────────────────────

    @Test
    void errorOperatorWithoutAnHttpClientInTheMethod_isNotReported() {
        // Found on vertx-examples and a Spring functional router: Handler.handle, a listen() chain and a filter.
        var method = service("filter", Set.of(), argumentCall(10, "handle", "request"),
                argumentCall(11, "exceptionally", "\"fallback\""));
        assertThat(new HttpClientErrorDiscardedRule().evaluate(flow(method))).isEmpty();
    }

    @Test
    void errorOperatorInAMethodThatCallsAWebClient_isReported() {
        var method = service("fetch", Set.of(), invocation(9, "webClient", "WebClient", "get"),
                argumentCall(10, "onErrorReturn", "\"unavailable\""));
        assertThat(new HttpClientErrorDiscardedRule().evaluate(flow(method))).hasSize(1);
    }

    // ── JDBC_RESOURCE_NOT_CLOSED ─────────────────────────────────────────────

    @Test
    void acquiredConnectionThatTheMethodReturns_isNotALeak() {
        var factory = method("example.Provider", "getConnection", Set.of(), "Connection", acquired("connection", false));
        assertThat(new JdbcResourceLeakRule().evaluate(flow(factory))).isEmpty();
    }

    @Test
    void acquiredConnectionKeptInAField_isNotALeakOfTheAcquiringMethod() {
        var method = method("example.Server", "setUp", Set.of(), "void", acquired("conn", true));
        assertThat(new JdbcResourceLeakRule().evaluate(flow(method))).isEmpty();
    }

    @Test
    void acquiredConnectionNeitherReturnedNorKept_isStillALeak() {
        var method = method("example.Dao", "count", Set.of(), "int", acquired("connection", false));
        assertThat(new JdbcResourceLeakRule().evaluate(flow(method))).hasSize(1);
    }

    // ── JPA_ENTITY_MANAGER_NOT_CLOSED ────────────────────────────────────────

    @Test
    void entityManagerThatTheMethodReturns_isNotALeak() {
        var factory = method("example.Provider", "open", Set.of(), "EntityManager", createdEntityManager("em", false));
        assertThat(new EntityManagerLeakRule().evaluate(flow(factory))).isEmpty();
    }

    @Test
    void entityManagerKeptInAField_isNotALeakOfTheCreatingMethod() {
        var method = method("example.Server", "setUp", Set.of(), "void", createdEntityManager("em", true));
        assertThat(new EntityManagerLeakRule().evaluate(flow(method))).isEmpty();
    }

    @Test
    void entityManagerNeitherReturnedNorKept_isStillALeak() {
        var method = method("example.Dao", "count", Set.of(), "int", createdEntityManager("em", false));
        assertThat(new EntityManagerLeakRule().evaluate(flow(method))).hasSize(1);
    }

    private static InvocationEvidence createdEntityManager(String variable, boolean field) {
        return new InvocationEvidence(location(10), "entityManagerFactory", "EntityManagerFactory",
                "createEntityManager", List.of(), InvocationResultUsage.ASSIGNED, false, false, variable, false, false,
                false, List.of(), false, false, field);
    }

    @Test
    void credentialNamedExceptionsAndSentencesAreNotSecrets() {
        var exception = service("a", Set.of("Service"), literalCall("BadCredentialsException", "\"Failed to get user permissions\""));
        var validator = service("b", Set.of("Service"), literalCall("validatePassword", "\"admin\""));
        var sentence = service("c", Set.of("Service"), literalCall("setPassword", "\"Empty password not supported\""));
        var real = service("d", Set.of("Service"), literalCall("setPassword", "\"s3cr3tValue\""));
        assertThat(new SecretInStringLiteralRule().evaluate(project(exception, validator, sentence))).isEmpty();
        assertThat(new SecretInStringLiteralRule().evaluate(project(real))).hasSize(1);
    }

    @Test
    void cacheableOnAJpaEntityClass_isNotSpringCaching() {
        var entityPrivate = method("example.Person", "age", Set.of("Entity", "Cacheable"), "int");
        var entityVoid = method("example.Person", "setName", Set.of("Entity", "Cacheable"), "void");
        assertThat(new CacheableOnVoidMethodRule().evaluate(project(entityVoid))).isEmpty();
        assertThat(new CacheEvictMissingRule().evaluate(project(entityPrivate, entityVoid))).isEmpty();
        var service = method("example.Svc", "setName", Set.of("Service", "Cacheable"), "void");
        assertThat(new CacheableOnVoidMethodRule().evaluate(project(service))).hasSize(1);
    }

    @Test
    void localParseFailureInATransactionalMethod_isNotSuppressedRollback() {
        var location = location(20);
        var parse = new CatchEvidence(location, "NumberFormatException", false, false, false, false, "");
        var data = new CatchEvidence(location(30), "DataAccessException", false, true, false, false, "");
        var method = new MethodModel(new MethodId("example.Svc", "run", List.of()), location(1), Set.of("Transactional"),
                List.of(parse, data), List.of(), List.of(), List.of(), List.of());
        assertThat(new TransactionalRollbackSuppressedRule().evaluate(flow(method))).hasSize(1);
    }

    @Test
    void resultSetFromANamedStatement_isCoveredByTheStatement() {
        var named = method("example.Dao", "count", Set.of(), "int", resultSetFrom("stmt", "ResultSet"));
        var chained = method("example.Dao", "count2", Set.of(), "int", resultSetFrom("connection.createStatement()", "Statement"));
        assertThat(new JdbcResourceLeakRule().evaluate(flow(named))).isEmpty();
        assertThat(new JdbcResourceLeakRule().evaluate(flow(chained))).hasSize(1);
    }

    @Test
    void getConnectionOnAFrameworkHandle_isNotAPoolAcquisition() {
        var liquibase = method("example.Change", "execute", Set.of(), "void", connectionFrom("database", "Database"));
        var pool = method("example.Dao", "count", Set.of(), "int", connectionFrom("ds", "HikariDataSource"));
        var unknown = method("example.Dao", "count2", Set.of(), "int", connectionFrom("dataSource", ""));
        assertThat(new JdbcResourceLeakRule().evaluate(flow(liquibase))).isEmpty();
        assertThat(new JdbcResourceLeakRule().evaluate(flow(pool))).hasSize(1);
        assertThat(new JdbcResourceLeakRule().evaluate(flow(unknown))).hasSize(1);
    }

    private static InvocationEvidence literalCall(String name, String argument) {
        return new InvocationEvidence(location(12), "", "", name, List.of(argument), InvocationResultUsage.IGNORED);
    }

    private static InvocationEvidence resultSetFrom(String scope, String type) {
        return new InvocationEvidence(location(10), scope, type, "executeQuery", List.of(),
                InvocationResultUsage.ASSIGNED, false, false, "rs", false, false, false, List.of(), false, false, false);
    }

    private static InvocationEvidence connectionFrom(String scope, String type) {
        return new InvocationEvidence(location(10), scope, type, "getConnection", List.of(),
                InvocationResultUsage.ASSIGNED, false, false, "connection", false, false, false, List.of(), false, false,
                false);
    }

    private static InvocationEvidence acquired(String variable, boolean field) {
        return new InvocationEvidence(location(10), "dataSource", "DataSource", "getConnection", List.of(),
                InvocationResultUsage.ASSIGNED, false, false, variable, false, false, false, List.of(), false, false,
                field);
    }

    private static MethodModel controllerPost(String name, List<String> parameterTypes, Map<String, String> parameters,
                                              String... annotations) {
        Set<String> names = annotations.length == 0
                ? Set.of("RestController", "PostMapping") : Set.of(annotations);
        Map<String, Map<String, String>> attributes = parameters.isEmpty()
                ? Map.of() : Map.of(MethodModel.PARAMETER_ANNOTATIONS, parameters);
        return new MethodModel(new MethodId("example.OrderController", name, parameterTypes), location(10), names,
                List.of(), List.of(), List.of(), List.of(), List.of(), ProxyProfile.unknown(), attributes,
                CallableShape.fixed(parameterTypes.size()), "void", false);
    }

    private static MethodModel service(String name, Set<String> annotations, InvocationEvidence... invocations) {
        return new MethodModel(new MethodId("example.Service", name, List.of()), location(1), annotations,
                List.of(), List.of(invocations), List.of(), List.of(), List.of());
    }

    private static MethodModel interfaceMethod(Set<String> annotations) {
        return new MethodModel(new MethodId("example.VetRepository", "findAll", List.of()), location(1), annotations,
                List.of(), List.of(), List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), "", true);
    }

    private static MethodModel method(String type, String name, Set<String> annotations, String returnType,
            InvocationEvidence... invocations) {
        return new MethodModel(new MethodId(type, name, List.of()), location(1), annotations, List.of(),
                List.of(invocations), List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), returnType, false);
    }

    private static MethodModel method(String type, String name, Set<String> annotations, String returnType) {
        return new MethodModel(new MethodId(type, name, List.of()), location(1), annotations, List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(), CallableShape.fixed(0),
                returnType, false);
    }

    private static MethodModel withInvocations(InvocationEvidence... invocations) {
        return new MethodModel(new MethodId("example.Service", "run", List.of()), location(1), Set.of(), List.of(),
                List.of(invocations), List.of(), List.of(), List.of());
    }

    private static MethodModel exceptionHandler(String name, String returnType) {
        return new MethodModel(new MethodId("example.Advice", name, List.of("RuntimeException")), location(1),
                Set.of("ExceptionHandler", "RestControllerAdvice"), List.of(), List.of(), List.of(), List.of(),
                List.of(), ProxyProfile.unknown(), Map.of(), CallableShape.fixed(1), returnType, false);
    }

    private static MethodModel scheduled(String name, String returnType) {
        return new MethodModel(new MethodId("example.Jobs", name, List.of()), location(1), Set.of("Scheduled"),
                List.of(), List.of(), List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                CallableShape.fixed(0), returnType, false);
    }

    private static MethodModel listener(String name, ProxyProfile proxy) {
        return new MethodModel(new MethodId("example.AuditService", name, List.of("Object")), location(1),
                Set.of("EventListener"), List.of(), List.of(), List.of(), List.of(), List.of(), proxy, Map.of(),
                CallableShape.fixed(1), "void", false);
    }

    private static MethodModel managedMethod(Set<String> annotations, ProxyProfile proxy) {
        return new MethodModel(new MethodId("example.GreetingResource", "greet", List.of()), location(1), annotations,
                List.of(), List.of(), List.of(), List.of(), List.of(), proxy, Map.of(), CallableShape.fixed(0),
                "String", false);
    }

    // ── Open-source corpus: ERROR rules that fired on text, not on code ─────────

    @Test
    void tokenizerAndTokenFilterSetters_areNotCredentials() {
        var tokenizer = argumentCall(10, "tokenizer", "\"standard\"");
        var filters = argumentCall(11, "tokenFilters", "\"asciifolding\"");
        var token = argumentCall(12, "setToken", "\"abcdef123456\"");

        assertThat(new SecretInStringLiteralRule().evaluate(project(service("analysis", Set.of(), tokenizer, filters, token))))
                .singleElement().satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(12));
    }

    @Test
    void aLogMessageThatMentionsPassword_isNotAPayload_butAConcatenationIs() {
        var message = loggerCall(10, "\"REST request to change password\"");
        var concatenated = loggerCall(11, "\"password=\" + password");
        var argument = loggerCall(12, "password");

        assertThat(new SensitivePayloadLoggedRule().evaluate(flow(service("change", Set.of(), message, concatenated, argument))))
                .extracting(finding -> finding.location().startLine()).containsExactlyInAnyOrder(11, 12);
    }

    @Test
    void aCatchInALambdaHandedToAnExecutor_doesNotDecideTheCallersTransaction() {
        var location = location(20);
        var offThread = new CatchEvidence(location, "Exception", false, false, false, false, "",
                false, false, false, true);
        var inline = new CatchEvidence(location(30), "Exception", false, false, false, false, "",
                false, false, false, false);
        var method = new MethodModel(new MethodId("example.Resource", "run", List.of()), location(1),
                Set.of("Transactional"), List.of(offThread, inline), List.of(), List.of(), List.of(), List.of());

        assertThat(new TransactionalRollbackSuppressedRule().evaluate(flow(method)))
                .singleElement().satisfies(finding -> assertThat(finding.location().startLine()).isEqualTo(30));
    }

    private static InvocationEvidence argumentCall(int line, String method, String argument) {
        return new InvocationEvidence(location(line), "builder", "Builder", method, List.of(argument),
                InvocationResultUsage.IGNORED);
    }

    private static InvocationEvidence loggerCall(int line, String argument) {
        return new InvocationEvidence(location(line), "log", "Logger", "info", List.of(argument),
                InvocationResultUsage.IGNORED, false, false, "", false, false, true);
    }

    // ── Proxy reach: what a class-level annotation and a non-public method really mean ─────

    @Test
    void aClassLevelAnnotationDoesNotPromiseAnythingForAPrivateMethod() {
        var inherited = new ProxyProfile(MethodVisibility.PRIVATE, false, false, false, true, false,
                Set.of("Transactional"), List.of(), Set.of());
        var declared = new ProxyProfile(MethodVisibility.PRIVATE, false, false, false, true, false,
                Set.of("Transactional"), List.of(), Set.of("Transactional"));

        assertThat(new NonProxyableAdviceTargetRule().evaluate(flow(proxied("clear", inherited)))).isEmpty();
        assertThat(new NonProxyableAdviceTargetRule().evaluate(flow(proxied("explicit", declared)))).hasSize(1);
    }

    @Test
    void protectedTransactionalWorksSinceSpring6_butProtectedCacheableDoesNot() {
        var transactional = new ProxyProfile(MethodVisibility.PROTECTED, false, false, false, true, false,
                Set.of("Transactional"), List.of());
        var cacheable = new ProxyProfile(MethodVisibility.PROTECTED, false, false, false, true, false,
                Set.of("Cacheable"), List.of());
        var packagePrivate = new ProxyProfile(MethodVisibility.PACKAGE_PRIVATE, false, false, false, true, false,
                Set.of("Transactional"), List.of());

        assertThat(new NonProxyableAdviceTargetRule().evaluate(flow(proxied("find", transactional)))).isEmpty();
        assertThat(new NonProxyableAdviceTargetRule().evaluate(flow(proxied("schedule", packagePrivate)))).isEmpty();
        assertThat(new NonProxyableAdviceTargetRule().evaluate(flow(proxied("lookup", cacheable)))).hasSize(1);
    }

    @Test
    void systemOutputInACommandLineProgram_isTheProgramsOutput() {
        var println = new InvocationEvidence(location(10), "System.out", "PrintStream", "println", List.of("\"done\""),
                InvocationResultUsage.IGNORED);
        var command = new MethodModel(new MethodId("example.ScanCommand", "call", List.of()), location(1),
                Set.of("Command"), List.of(), List.of(println), List.of(), List.of(), List.of());
        var main = new MethodModel(new MethodId("example.App", "main", List.of("String[]")), location(1), Set.of(),
                List.of(), List.of(println), List.of(), List.of(), List.of());
        var service = new MethodModel(new MethodId("example.OrderService", "place", List.of()), location(1),
                Set.of("Service"), List.of(), List.of(println), List.of(), List.of(), List.of());

        assertThat(new SystemOutputRule().evaluate(flow(command))).isEmpty();
        assertThat(new SystemOutputRule().evaluate(flow(main))).isEmpty();
        assertThat(new SystemOutputRule().evaluate(flow(service))).hasSize(1);
    }

    @Test
    void aNonBlockingHttpCallHoldsNoThread_unlessTheMethodWaitsForIt() {
        var reactive = service("reactive", Set.of(), invocation(10, "webClient", "WebClient", "retrieve"));
        var waiting = service("waiting", Set.of(), invocation(10, "webClient", "WebClient", "retrieve"),
                invocation(11, "spec", "Mono", "block"));
        var blocking = service("blocking", Set.of(), invocation(10, "restTemplate", "RestTemplate", "exchange"));
        var async = service("async", Set.of(), invocation(10, "httpClient", "HttpClient", "sendAsync"));

        var rule = new MissingBulkheadOnHttpClientRule();
        assertThat(rule.evaluate(flow(reactive))).isEmpty();
        assertThat(rule.evaluate(flow(async))).isEmpty();
        assertThat(rule.evaluate(flow(waiting))).hasSize(1);
        assertThat(rule.evaluate(flow(blocking))).hasSize(1);
    }

    // ── Quarkus and MicroProfile: Spring rules must not speak for CDI ────────────

    @Test
    void aJakartaTransactionalFinalMethod_isNotAProxyProblem() {
        var jakarta = method("example.LocationService", "deleteAll",
                Set.of("Final", "Transactional", MethodModel.JAKARTA_TRANSACTIONAL_ANNOTATION), "void");
        var spring = method("example.OrderService", "place", Set.of("Final", "Transactional"), "void");

        assertThat(new TransactionalOnFinalMethodRule().evaluate(project(jakarta))).isEmpty();
        assertThat(new TransactionalOnFinalMethodRule().evaluate(project(spring))).hasSize(1);
    }

    @Test
    void panacheDeleteAllInACdiBean_isABulkDelete() {
        var call = new InvocationEvidence(location(10), "locationRepository", "LocationRepository", "deleteAll",
                List.of(), InvocationResultUsage.IGNORED);
        var cdi = new MethodModel(new MethodId("example.LocationService", "reset", List.of()), location(1),
                Set.of("ApplicationScoped", "Transactional"), List.of(), List.of(call), List.of(), List.of(), List.of());
        var spring = new MethodModel(new MethodId("example.OwnerService", "reset", List.of()), location(1),
                Set.of("Service"), List.of(), List.of(call), List.of(), List.of(), List.of());

        assertThat(new DeleteAllWithoutIdRule().evaluate(project(cdi))).isEmpty();
        assertThat(new DeleteAllWithoutIdRule().evaluate(project(spring))).hasSize(1);
    }

    @Test
    void microProfileFaultToleranceAnnotationsHaveNoFallbackMethod() {
        var microProfile = new MethodModel(new MethodId("example.HeroClient", "find", List.of()), location(1),
                Set.of("CircuitBreaker", "Bulkhead"), List.of(), List.of(), List.of(), List.of(), List.of(),
                ProxyProfile.unknown(), Map.of("CircuitBreaker", Map.of("requestVolumeThreshold", "8"),
                        "Bulkhead", Map.of("value", "5")), CallableShape.fixed(0), "Uni<Hero>", false);
        var resilience4j = new MethodModel(new MethodId("example.PayClient", "pay", List.of()), location(1),
                Set.of("CircuitBreaker", "Bulkhead", "RateLimiter"), List.of(), List.of(), List.of(), List.of(), List.of(),
                ProxyProfile.unknown(), Map.of("CircuitBreaker", Map.of("name", "pay"), "Bulkhead", Map.of("name", "pay"),
                        "RateLimiter", Map.of("name", "pay")), CallableShape.fixed(0), "String", false);

        assertThat(new CircuitBreakerWithoutFallbackRule().evaluate(project(microProfile, resilience4j))).hasSize(1);
        assertThat(new BulkheadWithoutFallbackRule().evaluate(project(microProfile, resilience4j))).hasSize(1);
        assertThat(new RateLimiterWithoutFallbackRule().evaluate(project(microProfile, resilience4j))).hasSize(1);
    }

    @Test
    void aSpanThatIsReturnedIsClosedByTheCaller() {
        var returned = new InvocationEvidence(location(10), "spanBuilder", "SpanBuilder", "startSpan", List.of(),
                InvocationResultUsage.RETURNED);
        var dropped = new InvocationEvidence(location(11), "spanBuilder", "SpanBuilder", "startSpan", List.of(),
                InvocationResultUsage.IGNORED);

        assertThat(new SpanNotClosedRule().evaluate(flow(service("startsAndReturns", Set.of(), returned)))).isEmpty();
        assertThat(new SpanNotClosedRule().evaluate(flow(service("startsAndForgets", Set.of(), dropped)))).hasSize(1);
    }

    // ── Data classes are not god classes ────────────────────────────────────────

    @Test
    void gettersSettersAndObjectOverrides_doNotMakeAGodClass() {
        var methods = new java.util.ArrayList<MethodModel>();
        for (int i = 0; i < 8; i++) {
            methods.add(method("example.Stats", "getField" + i, Set.of(), "String"));
            methods.add(new MethodModel(new MethodId("example.Stats", "setField" + i, List.of("String")), location(1),
                    Set.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        }
        methods.add(method("example.Stats", "isActive", Set.of(), "boolean"));
        methods.add(method("example.Stats", "toString", Set.of(), "String"));
        methods.add(method("example.Stats", "hashCode", Set.of(), "int"));
        var behaviour = new java.util.ArrayList<MethodModel>();
        for (int i = 0; i < 16; i++) behaviour.add(method("example.Orders", "process" + i, Set.of(), "void"));

        assertThat(new GodClassRule().evaluate(project(methods.toArray(MethodModel[]::new)))).isEmpty();
        assertThat(new GodClassRule().evaluate(project(behaviour.toArray(MethodModel[]::new)))).hasSize(1);
    }

    @Test
    void aBuilderAndFluentMethodsAreNotAGodClass() {
        var builder = new java.util.ArrayList<MethodModel>();
        for (int i = 0; i < 20; i++) builder.add(method("example.Response.Builder", "field" + i, Set.of(), "Builder"));
        var immutable = new java.util.ArrayList<MethodModel>();
        for (int i = 0; i < 18; i++) immutable.add(method("example.Money", "with" + i, Set.of(), "Money"));
        var service = new java.util.ArrayList<MethodModel>();
        for (int i = 0; i < 16; i++) service.add(method("example.Orders", "process" + i, Set.of(), "Order"));

        assertThat(new GodClassRule().evaluate(project(builder.toArray(MethodModel[]::new)))).isEmpty();
        assertThat(new GodClassRule().evaluate(project(immutable.toArray(MethodModel[]::new)))).isEmpty();
        assertThat(new GodClassRule().evaluate(project(service.toArray(MethodModel[]::new)))).hasSize(1);
    }

    @Test
    void anInterfaceIsAContract_notAGodClass() {
        var contract = new java.util.ArrayList<MethodModel>();
        for (int i = 0; i < 20; i++) {
            contract.add(new MethodModel(new MethodId("example.UserRepository", "findBy" + i, List.of()), location(1),
                    Set.of(), List.of(), List.of(), List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(),
                    CallableShape.fixed(0), "List<User>", true));
        }

        assertThat(new GodClassRule().evaluate(project(contract.toArray(MethodModel[]::new)))).isEmpty();
    }

    // ── Noise from repeated or delegating findings ───────────────────────────

    @Test
    void transactionalControllerMethods_areReportedOncePerClass() {
        var annotations = Set.of("RestController", "Transactional");
        var list = method("example.web.OwnerResource", "list", annotations, "List<Owner>");
        var get = method("example.web.OwnerResource", "get", annotations, "Owner");
        var other = method("example.web.PetResource", "get", annotations, "Pet");
        var service = method("example.service.OwnerService", "list", Set.of("Transactional"), "List<Owner>");

        var findings = new TransactionalOnRestControllerRule().evaluate(project(list, get, other, service));

        assertThat(findings).hasSize(2);
        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.evidence()).containsEntry("declaringType", "example.web.OwnerResource")
                    .containsEntry("methodCount", "2").containsEntry("methods", "get, list");
            assertThat(finding.message()).contains("2 methods of 'example.web.OwnerResource'");
        });
        assertThat(findings).anySatisfy(finding -> assertThat(finding.message())
                .startsWith("'example.web.PetResource.get()' is both"));
    }

    @Test
    void aDefaultRepositoryMethod_isNotReportedBesideTheQueryItDelegatesTo() {
        var query = method("example.OwnerRepository", "findAllWithToOneRelationships", Set.of("Repository"),
                "List<Owner>");
        var delegating = method("example.OwnerRepository", "findAllWithEagerRelationships",
                Set.of("Repository", MethodModel.IMPLEMENTED_IN_INTERFACE_ANNOTATION), "List<Owner>");

        assertThat(new MissingPaginationRule().evaluate(project(query, delegating)))
                .singleElement().satisfies(finding -> assertThat(finding.evidence())
                        .containsEntry("method", query.id().displayName()));
    }

    private static MethodModel proxied(String name, ProxyProfile proxy) {
        return new MethodModel(new MethodId("example.Users", name, List.of()), location(1), Set.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), proxy);
    }

    private static MethodModel repositoryMethod(String name, String returnType, List<String> parameterTypes) {
        return new MethodModel(new MethodId("example.OwnerRepository", name, parameterTypes), location(1),
                Set.of("Repository"), List.of(), List.of(), List.of(), List.of(), List.of(), ProxyProfile.unknown(),
                Map.of(), CallableShape.fixed(parameterTypes.size()), returnType, true);
    }

    private static InvocationEvidence save(int line, String scope) {
        return invocation(line, scope, "Repository", "save");
    }

    private static InvocationEvidence invocation(int line, String scope, String receiverType, String method) {
        return new InvocationEvidence(location(line), scope, receiverType, method, List.of(),
                InvocationResultUsage.IGNORED);
    }

    private static Flow flow(MethodModel method) {
        var entrypoint = new Entrypoint(EntrypointType.REST, method.id(), "POST /x", method.location());
        return new Flow(entrypoint, List.of(new FlowMethod(method, 0, Confidence.HIGH, List.of(method.id()))),
                List.of());
    }

    private static AnalyzedProject project(MethodModel... methods) {
        var map = new LinkedHashMap<MethodId, MethodModel>();
        for (var method : methods) map.put(method.id(), method);
        var root = Path.of(".");
        return new AnalyzedProject("test", root,
                new ProjectLayout(BuildSystem.MAVEN, root, List.of(), List.of(root)), map, List.of(), 1L, List.of());
    }

    private static SourceLocation location(int line) {
        return new SourceLocation(Path.of("src/main/java/example/Sample.java"), line, line);
    }
}
