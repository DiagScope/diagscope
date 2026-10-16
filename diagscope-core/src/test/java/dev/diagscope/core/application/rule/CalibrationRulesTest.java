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

    // ── MASS_ASSIGNMENT_RISK ─────────────────────────────────────────────────

    @Test
    void controllerWithInitBinder_isNotReported() {
        var entity = method("example.Owner", "getId", Set.of("Entity"), "Long");
        var endpoint = controllerPost("create", List.of("Owner"), Map.of("p0", "RequestBody"));
        var binder = method("example.OrderController", "setAllowedFields", Set.of("InitBinder"), "void");
        assertThat(new MassAssignmentRiskRule().evaluate(project(entity, endpoint))).hasSize(1);
        assertThat(new MassAssignmentRiskRule().evaluate(project(entity, endpoint, binder))).isEmpty();
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

    // ── helpers ──────────────────────────────────────────────────────────────

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

    private static MethodModel method(String type, String name, Set<String> annotations, String returnType) {
        return new MethodModel(new MethodId(type, name, List.of()), location(1), annotations, List.of(), List.of(),
                List.of(), List.of(), List.of(), ProxyProfile.unknown(), Map.of(), CallableShape.fixed(0),
                returnType, false);
    }

    private static MethodModel managedMethod(Set<String> annotations, ProxyProfile proxy) {
        return new MethodModel(new MethodId("example.GreetingResource", "greet", List.of()), location(1), annotations,
                List.of(), List.of(), List.of(), List.of(), List.of(), proxy, Map.of(), CallableShape.fixed(0),
                "String", false);
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
