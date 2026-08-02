package dev.diagscope.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.port.out.ProjectAnalyzer;
import dev.diagscope.core.application.port.out.UnsupportedProjectException;
import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.CatchEvidence;
import dev.diagscope.core.domain.Entrypoint;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodCall;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.MetricTagEvidence;
import dev.diagscope.core.domain.ParseFailure;
import dev.diagscope.core.domain.ResolutionReason;
import dev.diagscope.core.domain.SourceLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** Syntax-first Java source adapter for the alpha release. */
public final class JavaParserProjectAnalyzer implements ProjectAnalyzer {
    private static final Set<String> REST_MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping"
    );
    private static final Set<String> LOGGER_METHODS = Set.of("trace", "debug", "info", "warn", "error", "log");
    private static final Set<String> OBSERVING_COMPLETION_METHODS = Set.of(
            "get", "join", "whenComplete", "handle", "exceptionally", "thenAccept", "thenRun"
    );
    private static final Set<String> MICROMETER_BUILDERS = Set.of(
            "Counter", "Timer", "Gauge", "DistributionSummary", "LongTaskTimer", "FunctionCounter", "Tags"
    );
    private static final Pattern STABLE_FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{2,}");
    private static final Pattern SILENT_CATCH_SUPPRESSION = Pattern.compile(
            "(?i)diagscope\\s*:\\s*ignore\\s+SILENT_CATCH\\s*--\\s*\\S.*"
    );

    @Override
    public AnalyzedProject analyze(Path projectDirectory, AnalysisOptions options) {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(options, "options");
        Path root = projectDirectory.toAbsolutePath().normalize();
        Path sourceRoot = requireSupportedProject(root);

        List<Path> sourceFiles = discoverSourceFiles(sourceRoot);
        ParseBatch parseBatch = parseFiles(root, sourceFiles, options.parallelism());
        MappedProject mapped = mergeMappedUnits(parseBatch.units());
        Map<MethodId, MethodModel> methods = resolveLocalCalls(mapped);
        List<Entrypoint> entrypoints = detectEntrypoints(mapped.rawMethods(), options.enabledEntrypointTypes());

        var failures = new ArrayList<ParseFailure>(parseBatch.failures().size() + mapped.failures().size());
        failures.addAll(parseBatch.failures());
        failures.addAll(mapped.failures());
        failures.sort(Comparator.comparing(failure -> failure.file().toString()));
        return new AnalyzedProject(root.getFileName().toString(), root, methods, entrypoints,
                sourceFiles.size(), failures);
    }

    private static Path requireSupportedProject(Path root) {
        if (!Files.isRegularFile(root.resolve("pom.xml"))) {
            throw new UnsupportedProjectException("No pom.xml found in " + root);
        }
        Path sourceRoot = root.resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            throw new UnsupportedProjectException("No src/main/java directory found in " + root);
        }
        return sourceRoot;
    }

    private static List<Path> discoverSourceFiles(Path sourceRoot) {
        try (var stream = Files.find(sourceRoot, Integer.MAX_VALUE,
                (path, attributes) -> attributes.isRegularFile() && path.toString().endsWith(".java"))) {
            return stream.sorted().toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to discover Java sources under " + sourceRoot, exception);
        }
    }

    private static ParseBatch parseFiles(Path root, List<Path> files, int parallelism) {
        var units = new MappedUnit[files.size()];
        var failures = new ParseFailure[files.size()];
        var configuration = new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25);
        var pool = new ForkJoinPool(Math.min(parallelism, Math.max(1, files.size())));
        try {
            pool.submit(() -> IntStream.range(0, files.size()).parallel().forEach(index -> {
                Path file = files.get(index);
                Path relativeFile = root.relativize(file.toAbsolutePath().normalize());
                try {
                    var result = new JavaParser(configuration).parse(file);
                    result.getResult().ifPresent(unit -> units[index] = mapUnit(root, file, unit));
                    if (!result.getProblems().isEmpty()) {
                        failures[index] = new ParseFailure(relativeFile, problemMessage(result.getProblems()));
                    } else if (result.getResult().isEmpty()) {
                        failures[index] = new ParseFailure(relativeFile, "JavaParser produced no compilation unit");
                    }
                } catch (IOException | RuntimeException exception) {
                    failures[index] = new ParseFailure(relativeFile,
                            exception.getClass().getSimpleName() + ": " + safeMessage(exception));
                }
            })).join();
        } finally {
            pool.shutdown();
        }
        return new ParseBatch(
                Arrays.stream(units).filter(Objects::nonNull).toList(),
                Arrays.stream(failures).filter(Objects::nonNull).toList()
        );
    }

    private static String problemMessage(List<Problem> problems) {
        return problems.stream().limit(3).map(Problem::getVerboseMessage)
                .map(message -> message.replace('\r', ' ').replace('\n', ' '))
                .reduce((left, right) -> left + " | " + right)
                .orElse("Unknown parser problem");
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? "No diagnostic message"
                : throwable.getMessage();
    }

    private static MappedUnit mapUnit(Path root, Path file, CompilationUnit unit) {
        var rawMethods = new ArrayList<RawMethod>();
        var types = new ArrayList<TypeInfo>();
        String packageName = unit.getPackageDeclaration().map(node -> node.getNameAsString()).orElse("");
        for (Node node : unit.findAll(Node.class, TypeDeclaration.class::isInstance)) {
            TypeDeclaration<?> type = (TypeDeclaration<?>) node;
            if (hasMethodAncestor(type)) continue;
            String qualifiedName = qualifiedTypeName(packageName, type);
            types.add(typeInfo(qualifiedName, type));
            Map<String, String> declaredVariables = declaredTypeVariables(type);
            List<AnnotationDescriptor> typeAnnotations = annotations(type.getAnnotations());

            for (BodyDeclaration<?> member : type.getMembers()) {
                if (!member.isMethodDeclaration()) continue;
                rawMethods.add(mapMethod(root, file, qualifiedName, member.asMethodDeclaration(),
                        declaredVariables, typeAnnotations));
            }
        }
        return new MappedUnit(root.relativize(file.toAbsolutePath().normalize()),
                List.copyOf(rawMethods), List.copyOf(types));
    }

    private static MappedProject mergeMappedUnits(List<MappedUnit> units) {
        var rawMethods = new LinkedHashMap<MethodId, RawMethod>(Math.max(16, units.size() * 4));
        var types = new LinkedHashMap<String, TypeInfo>();
        var failures = new ArrayList<ParseFailure>();

        for (var unit : units) {
            for (var type : unit.types()) {
                TypeInfo previous = types.putIfAbsent(type.qualifiedName(), type);
                if (previous != null) {
                    failures.add(new ParseFailure(unit.file(),
                            "Duplicate type identity ignored: " + type.qualifiedName()));
                }
            }
            for (var rawMethod : unit.rawMethods()) {
                RawMethod previous = rawMethods.putIfAbsent(rawMethod.id(), rawMethod);
                if (previous != null) {
                    failures.add(new ParseFailure(rawMethod.location().file(),
                            "Duplicate method identity ignored: " + rawMethod.id().displayName()));
                }
            }
        }
        return new MappedProject(
                Collections.unmodifiableMap(new LinkedHashMap<>(rawMethods)),
                Collections.unmodifiableMap(new LinkedHashMap<>(types)),
                List.copyOf(failures)
        );
    }

    private static RawMethod mapMethod(
            Path root,
            Path file,
            String declaringType,
            MethodDeclaration method,
            Map<String, String> declaredVariables,
            List<AnnotationDescriptor> typeAnnotations
    ) {
        MethodId id = methodId(declaringType, method);
        SourceLocation methodLocation = location(root, file, method);
        List<AnnotationDescriptor> methodAnnotations = annotations(method.getAnnotations());
        var annotationNames = new TreeSet<String>();
        typeAnnotations.forEach(annotation -> annotationNames.add(annotation.name()));
        methodAnnotations.forEach(annotation -> annotationNames.add(annotation.name()));

        var variableTypes = new LinkedHashMap<>(declaredVariables);
        method.getParameters().forEach(parameter ->
                variableTypes.put(parameter.getNameAsString(), parameter.getTypeAsString()));
        method.findAll(VariableDeclarator.class).stream()
                .filter(variable -> belongsToMethod(variable, method))
                .forEach(variable -> variableTypes.put(variable.getNameAsString(), variable.getTypeAsString()));
        method.findAll(CatchClause.class).stream()
                .filter(clause -> belongsToMethod(clause, method))
                .forEach(clause -> variableTypes.put(
                        clause.getParameter().getNameAsString(), clause.getParameter().getTypeAsString()));

        var catches = method.findAll(CatchClause.class).stream()
                .filter(clause -> belongsToMethod(clause, method))
                .map(clause -> catchEvidence(root, file, clause, variableTypes))
                .sorted(Comparator.comparingInt(evidence -> evidence.location().startLine()))
                .toList();

        var invocations = new ArrayList<InvocationEvidence>();
        var metricTags = new ArrayList<MetricTagEvidence>();
        var calls = new ArrayList<RawCall>();
        List<MethodCallExpr> methodCalls = method.findAll(MethodCallExpr.class).stream()
                .filter(call -> belongsToMethod(call, method))
                .sorted(Comparator.comparingInt(call -> call.getBegin().map(position -> position.line).orElse(1)))
                .toList();
        for (var call : methodCalls) {
            invocations.add(invocationEvidence(root, file, call, variableTypes));
            metricTagEvidence(root, file, call, variableTypes).ifPresent(metricTags::add);
            calls.add(new RawCall(location(root, file, call), scope(call), call.getNameAsString(),
                    call.getArguments().size()));
        }

        return new RawMethod(id, methodLocation, Set.copyOf(annotationNames), typeAnnotations, methodAnnotations,
                catches, List.copyOf(invocations), List.copyOf(metricTags), List.copyOf(calls),
                Collections.unmodifiableMap(new LinkedHashMap<>(variableTypes)));
    }

    private static boolean belongsToMethod(Node node, MethodDeclaration method) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof MethodDeclaration ancestor) return method.equals(ancestor);
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private static boolean hasMethodAncestor(Node node) {
        Node current = node.getParentNode().orElse(null);
        while (current != null) {
            if (current instanceof MethodDeclaration) return true;
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    private static Map<MethodId, MethodModel> resolveLocalCalls(MappedProject project) {
        var byQualifiedTypeAndName = new HashMap<String, List<MethodId>>();
        var bySimpleTypeAndName = new HashMap<String, List<MethodId>>();
        for (var id : project.rawMethods().keySet()) {
            byQualifiedTypeAndName.computeIfAbsent(id.declaringType() + '#' + id.name(), ignored -> new ArrayList<>())
                    .add(id);
            bySimpleTypeAndName.computeIfAbsent(simpleName(id.declaringType()) + '#' + id.name(), ignored -> new ArrayList<>())
                    .add(id);
        }
        byQualifiedTypeAndName.values().forEach(list -> list.sort(Comparator.comparing(MethodId::displayName)));
        bySimpleTypeAndName.values().forEach(list -> list.sort(Comparator.comparing(MethodId::displayName)));

        var typesBySimpleName = new HashMap<String, List<TypeInfo>>();
        project.types().values().forEach(type -> typesBySimpleName
                .computeIfAbsent(type.simpleName(), ignored -> new ArrayList<>()).add(type));
        typesBySimpleName.values().forEach(list -> list.sort(Comparator.comparing(TypeInfo::qualifiedName)));
        var implementationsBySuperType = new HashMap<String, List<TypeInfo>>();
        for (var type : project.types().values()) {
            for (String superType : type.superTypes()) {
                implementationsBySuperType.computeIfAbsent(simpleName(superType), ignored -> new ArrayList<>())
                        .add(type);
            }
        }
        implementationsBySuperType.values().forEach(list ->
                list.sort(Comparator.comparing(TypeInfo::qualifiedName)));

        var resolved = new LinkedHashMap<MethodId, MethodModel>(project.rawMethods().size());
        for (var raw : project.rawMethods().values()) {
            var calls = new ArrayList<MethodCall>(raw.calls().size());
            for (var call : raw.calls()) {
                Resolution resolution = resolveCall(raw, call, project.types(), typesBySimpleName,
                        implementationsBySuperType, byQualifiedTypeAndName, bySimpleTypeAndName);
                calls.add(new MethodCall(call.location(), call.scope(), call.methodName(), call.argumentCount(),
                        resolution.target(), resolution.reason()));
            }
            resolved.put(raw.id(), new MethodModel(raw.id(), raw.location(), raw.annotationNames(), raw.catches(),
                    raw.invocations(), raw.metricTags(), calls));
        }
        return Collections.unmodifiableMap(resolved);
    }

    private static Resolution resolveCall(
            RawMethod raw,
            RawCall call,
            Map<String, TypeInfo> types,
            Map<String, List<TypeInfo>> typesBySimpleName,
            Map<String, List<TypeInfo>> implementationsBySuperType,
            Map<String, List<MethodId>> byQualifiedTypeAndName,
            Map<String, List<MethodId>> bySimpleTypeAndName
    ) {
        String scope = call.scope();
        if (scope.isBlank() || "this".equals(scope)) {
            return choose(byQualifiedTypeAndName.get(raw.id().declaringType() + '#' + call.methodName()),
                    call.argumentCount(), ResolutionReason.SAME_CLASS);
        }
        if ("super".equals(scope)) {
            return new Resolution(Optional.empty(), ResolutionReason.UNRESOLVED);
        }

        String declaredType = receiverType(scope, raw.variableTypes());
        if (declaredType != null) {
            String receiver = simpleName(declaredType);
            List<TypeInfo> receiverTypes = typesBySimpleName.getOrDefault(receiver, List.of());
            if (receiverTypes.size() > 1 && !types.containsKey(baseTypeName(declaredType))) {
                return new Resolution(Optional.empty(), ResolutionReason.AMBIGUOUS);
            }
            TypeInfo receiverInfo = Optional.ofNullable(types.get(baseTypeName(declaredType)))
                    .orElseGet(() -> receiverTypes.size() == 1 ? receiverTypes.getFirst() : null);

            if (receiverInfo != null && !receiverInfo.interfaceType()) {
                Resolution direct = choose(
                        byQualifiedTypeAndName.get(receiverInfo.qualifiedName() + '#' + call.methodName()),
                        call.argumentCount(), ResolutionReason.DECLARED_RECEIVER);
                if (direct.target().isPresent() || direct.reason() == ResolutionReason.AMBIGUOUS) return direct;
                return new Resolution(Optional.empty(), ResolutionReason.UNRESOLVED);
            }
            if (receiverInfo == null) {
                return new Resolution(Optional.empty(), ResolutionReason.EXTERNAL);
            }

            var implementationCandidates = new ArrayList<MethodId>();
            for (var type : implementationsBySuperType.getOrDefault(receiver, List.of())) {
                List<MethodId> methods = byQualifiedTypeAndName.get(type.qualifiedName() + '#' + call.methodName());
                if (methods != null) {
                    methods.stream().filter(candidate -> candidate.parameterTypes().size() == call.argumentCount())
                            .forEach(implementationCandidates::add);
                }
            }
            if (implementationCandidates.size() == 1) {
                return new Resolution(Optional.of(implementationCandidates.getFirst()), ResolutionReason.SINGLE_IMPLEMENTATION);
            }
            if (implementationCandidates.size() > 1) {
                return new Resolution(Optional.empty(), ResolutionReason.AMBIGUOUS);
            }
            return new Resolution(Optional.empty(), ResolutionReason.UNRESOLVED);
        }

        String possibleType = simpleName(scope);
        if (typesBySimpleName.containsKey(possibleType)) {
            return choose(bySimpleTypeAndName.get(possibleType + '#' + call.methodName()),
                    call.argumentCount(), ResolutionReason.DECLARED_RECEIVER);
        }
        if (looksExternal(scope)) {
            return new Resolution(Optional.empty(), ResolutionReason.EXTERNAL);
        }
        return new Resolution(Optional.empty(), ResolutionReason.UNRESOLVED);
    }

    private static Resolution choose(List<MethodId> candidates, int arity, ResolutionReason successReason) {
        if (candidates == null) return new Resolution(Optional.empty(), ResolutionReason.UNRESOLVED);
        List<MethodId> matches = candidates.stream()
                .filter(candidate -> candidate.parameterTypes().size() == arity)
                .limit(2)
                .toList();
        if (matches.size() == 1) return new Resolution(Optional.of(matches.getFirst()), successReason);
        if (matches.size() > 1) return new Resolution(Optional.empty(), ResolutionReason.AMBIGUOUS);
        return new Resolution(Optional.empty(), ResolutionReason.UNRESOLVED);
    }

    private static boolean looksExternal(String scope) {
        if (scope.startsWith("System.") || scope.startsWith("java.")) return true;
        int separator = scope.indexOf('.');
        String root = separator < 0 ? scope : scope.substring(0, separator);
        return !root.isBlank() && Character.isUpperCase(root.charAt(0));
    }

    private static List<Entrypoint> detectEntrypoints(
            Map<MethodId, RawMethod> methods,
            Set<EntrypointType> enabledTypes
    ) {
        var result = new ArrayList<Entrypoint>();
        for (var method : methods.values()) {
            if (enabledTypes.contains(EntrypointType.REST)) {
                detectRestEntrypoint(method).ifPresent(result::add);
            }
            if (enabledTypes.contains(EntrypointType.KAFKA_LISTENER)) {
                annotation(method.methodAnnotations(), "KafkaListener").ifPresent(annotation -> {
                    String topic = firstAttribute(annotation, "topics", "value").orElse("unknown");
                    result.add(new Entrypoint(EntrypointType.KAFKA_LISTENER, method.id(),
                            "Kafka topic=" + topic, method.location()));
                });
            }
            if (enabledTypes.contains(EntrypointType.SCHEDULED)) {
                annotation(method.methodAnnotations(), "Scheduled").ifPresent(annotation -> {
                    String schedule = scheduleDisplay(annotation);
                    result.add(new Entrypoint(EntrypointType.SCHEDULED, method.id(), schedule, method.location()));
                });
            }
        }
        result.sort(Comparator.comparing((Entrypoint entrypoint) -> entrypoint.type().name())
                .thenComparing(Entrypoint::displayName)
                .thenComparing(entrypoint -> entrypoint.method().displayName()));
        return List.copyOf(result);
    }

    private static Optional<Entrypoint> detectRestEntrypoint(RawMethod method) {
        boolean controller = annotation(method.typeAnnotations(), "RestController").isPresent()
                || annotation(method.typeAnnotations(), "Controller").isPresent();
        if (!controller) return Optional.empty();
        Optional<AnnotationDescriptor> mapping = method.methodAnnotations().stream()
                .filter(annotation -> REST_MAPPING_ANNOTATIONS.contains(annotation.name()))
                .findFirst();
        if (mapping.isEmpty()) return Optional.empty();

        String prefix = annotation(method.typeAnnotations(), "RequestMapping")
                .flatMap(annotation -> firstAttribute(annotation, "path", "value"))
                .orElse("");
        String methodPath = firstAttribute(mapping.orElseThrow(), "path", "value").orElse("");
        String path = combinePaths(prefix, methodPath);
        String verb = restVerb(mapping.orElseThrow());
        return Optional.of(new Entrypoint(EntrypointType.REST, method.id(), verb + ' ' + path, method.location()));
    }

    private static String restVerb(AnnotationDescriptor annotation) {
        return switch (annotation.name()) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "PatchMapping" -> "PATCH";
            case "DeleteMapping" -> "DELETE";
            case "RequestMapping" -> annotation.attributes().getOrDefault("method", "HTTP")
                    .replace("RequestMethod.", "").replace("{", "").replace("}", "")
                    .split(",", 2)[0].trim();
            default -> "HTTP";
        };
    }

    private static String combinePaths(String prefix, String suffix) {
        String left = normalizePath(prefix);
        String right = normalizePath(suffix);
        if ("/".equals(left)) return right;
        if ("/".equals(right)) return left;
        return normalizePath(left + '/' + right.substring(1));
    }

    private static String normalizePath(String path) {
        String value = path == null ? "" : path.trim();
        if (value.isBlank()) return "/";
        if (!value.startsWith("/")) value = '/' + value;
        while (value.length() > 1 && value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.replaceAll("/{2,}", "/");
    }

    private static String scheduleDisplay(AnnotationDescriptor annotation) {
        for (String attribute : List.of("cron", "fixedRateString", "fixedRate", "fixedDelayString", "fixedDelay")) {
            String value = annotation.attributes().get(attribute);
            if (value != null && !value.isBlank()) return "Scheduled " + attribute + '=' + value;
        }
        return "Scheduled";
    }

    private static Optional<AnnotationDescriptor> annotation(List<AnnotationDescriptor> annotations, String name) {
        return annotations.stream().filter(annotation -> name.equals(annotation.name())).findFirst();
    }

    private static Optional<String> firstAttribute(AnnotationDescriptor annotation, String... names) {
        for (String name : names) {
            String value = annotation.attributes().get(name);
            if (value != null && !value.isBlank()) {
                return Optional.of(value.split(",", 2)[0].trim());
            }
        }
        return Optional.empty();
    }

    private static CatchEvidence catchEvidence(
            Path root,
            Path file,
            CatchClause clause,
            Map<String, String> variableTypes
    ) {
        var body = clause.getBody();
        boolean hasLog = body.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> isLoggerCall(call, variableTypes));
        boolean hasThrow = !body.findAll(ThrowStmt.class).isEmpty();
        List<ReturnStmt> returns = body.findAll(ReturnStmt.class);
        Optional<Expression> returned = returns.stream().findFirst().flatMap(ReturnStmt::getExpression);
        String exceptionVariable = clause.getParameter().getNameAsString();
        boolean preservesCause = returned.stream().anyMatch(expression -> expression.findAll(NameExpr.class).stream()
                .anyMatch(name -> exceptionVariable.equals(name.getNameAsString())));
        boolean stableFailureCode = returned.stream().anyMatch(JavaParserProjectAnalyzer::containsStableFailureCode);
        boolean suppression = body.getAllContainedComments().stream().map(Comment::getContent)
                .anyMatch(content -> SILENT_CATCH_SUPPRESSION.matcher(content.trim()).find());
        return new CatchEvidence(
                location(root, file, clause),
                clause.getParameter().getTypeAsString(),
                body.getStatements().isEmpty(),
                hasLog,
                hasThrow,
                !returns.isEmpty(),
                returned.map(Node::toString).orElse(""),
                suppression,
                preservesCause,
                stableFailureCode
        );
    }

    private static boolean isLoggerCall(MethodCallExpr call, Map<String, String> variableTypes) {
        if (!LOGGER_METHODS.contains(call.getNameAsString())) return false;
        String rawScope = scope(call);
        String receiverType = Optional.ofNullable(receiverType(rawScope, variableTypes)).orElse("");
        String hint = (rawScope + ' ' + receiverType).toLowerCase(Locale.ROOT);
        return hint.contains("logger") || hint.matches(".*\\blog\\b.*");
    }

    private static boolean containsStableFailureCode(Expression expression) {
        boolean literal = expression.findAll(StringLiteralExpr.class).stream()
                .map(StringLiteralExpr::asString)
                .anyMatch(value -> STABLE_FAILURE_CODE.matcher(value).matches());
        if (literal) return true;
        return expression.findAll(FieldAccessExpr.class).stream()
                .map(FieldAccessExpr::getNameAsString)
                .anyMatch(value -> STABLE_FAILURE_CODE.matcher(value).matches());
    }

    private static InvocationEvidence invocationEvidence(
            Path root,
            Path file,
            MethodCallExpr call,
            Map<String, String> variableTypes
    ) {
        String rawScope = scope(call);
        String receiverType = Optional.ofNullable(receiverType(rawScope, variableTypes))
                .orElseGet(() -> inferReceiverType(rawScope));
        return new InvocationEvidence(
                location(root, file, call),
                rawScope,
                receiverType,
                call.getNameAsString(),
                call.getArguments().stream().map(Node::toString).toList(),
                resultUsage(call)
        );
    }

    private static String inferReceiverType(String rawScope) {
        if (rawScope.isBlank()) return "";
        if (rawScope.startsWith("System.")) return rawScope;
        int separator = rawScope.indexOf('.');
        String root = separator < 0 ? rawScope : rawScope.substring(0, separator);
        return !root.isBlank() && Character.isUpperCase(root.charAt(0)) ? root : "";
    }

    private static InvocationResultUsage resultUsage(MethodCallExpr call) {
        Node current = call;
        while (current.getParentNode().isPresent()) {
            Node parent = current.getParentNode().orElseThrow();
            if (parent instanceof ExpressionStmt) return InvocationResultUsage.IGNORED;
            if (parent instanceof ReturnStmt) return InvocationResultUsage.RETURNED;
            if (parent instanceof VariableDeclarator variable && variable.getInitializer().orElse(null) == current) {
                return InvocationResultUsage.ASSIGNED;
            }
            if (parent instanceof AssignExpr assignment && assignment.getValue() == current) {
                return InvocationResultUsage.ASSIGNED;
            }
            if (parent instanceof MethodCallExpr outer) {
                if (outer.getArguments().contains(current)) return InvocationResultUsage.USED_AS_ARGUMENT;
                return OBSERVING_COMPLETION_METHODS.contains(outer.getNameAsString())
                        ? InvocationResultUsage.OBSERVED
                        : InvocationResultUsage.CHAINED;
            }
            if (parent instanceof Expression || parent instanceof ArrayInitializerExpr) {
                current = parent;
                continue;
            }
            break;
        }
        return InvocationResultUsage.UNKNOWN;
    }

    private static Optional<MetricTagEvidence> metricTagEvidence(
            Path root,
            Path file,
            MethodCallExpr call,
            Map<String, String> variableTypes
    ) {
        if (!"tag".equals(call.getNameAsString()) || call.getArguments().size() < 2) return Optional.empty();
        String rawScope = scope(call);
        boolean micrometer = isMicrometerTagScope(rawScope, variableTypes);
        if (!micrometer) return Optional.empty();
        String tagName = call.getArgument(0) instanceof StringLiteralExpr literal
                ? literal.asString()
                : call.getArgument(0).toString();
        Expression value = call.getArgument(1);
        boolean uuid = value.findAll(NameExpr.class).stream()
                .map(name -> variableTypes.getOrDefault(name.getNameAsString(), ""))
                .map(JavaParserProjectAnalyzer::simpleName)
                .anyMatch("UUID"::equals)
                || value.toString().toLowerCase(Locale.ROOT).contains("uuid");
        boolean unbounded = uuid || value.findAll(NameExpr.class).stream()
                .map(NameExpr::getNameAsString)
                .anyMatch(JavaParserProjectAnalyzer::looksUnboundedIdentifier);
        return Optional.of(new MetricTagEvidence(location(root, file, call), tagName, value.toString(),
                true, uuid, unbounded));
    }

    private static boolean isMicrometerTagScope(String rawScope, Map<String, String> variableTypes) {
        String receiverType = simpleName(Optional.ofNullable(receiverType(rawScope, variableTypes)).orElse(""));
        if (MICROMETER_BUILDERS.contains(receiverType)) return true;
        for (String builder : MICROMETER_BUILDERS) {
            if (rawScope.contains(builder + ".builder") || rawScope.startsWith(builder + ".of")) return true;
        }
        return false;
    }

    private static boolean looksUnboundedIdentifier(String name) {
        String normalized = name.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return normalized.endsWith("id") || normalized.contains("uuid") || normalized.contains("email")
                || normalized.contains("token") || normalized.contains("requestid")
                || normalized.contains("traceid") || normalized.contains("spanid");
    }

    private static MethodId methodId(String declaringType, MethodDeclaration method) {
        return new MethodId(declaringType, method.getNameAsString(),
                method.getParameters().stream().map(parameter -> parameter.getTypeAsString()).toList());
    }

    private static Map<String, String> declaredTypeVariables(TypeDeclaration<?> type) {
        var result = new LinkedHashMap<String, String>();
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration field) {
                field.getVariables().forEach(variable ->
                        result.put(variable.getNameAsString(), variable.getTypeAsString()));
            }
        }
        if (type instanceof RecordDeclaration record) {
            record.getParameters().forEach(parameter ->
                    result.put(parameter.getNameAsString(), parameter.getTypeAsString()));
        }
        return result;
    }

    private static TypeInfo typeInfo(String qualifiedName, TypeDeclaration<?> type) {
        var superTypes = new LinkedHashSet<String>();
        boolean interfaceType = false;
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            interfaceType = declaration.isInterface();
            declaration.getExtendedTypes().forEach(parent -> superTypes.add(parent.getNameWithScope()));
            declaration.getImplementedTypes().forEach(parent -> superTypes.add(parent.getNameWithScope()));
        } else if (type instanceof EnumDeclaration declaration) {
            declaration.getImplementedTypes().forEach(parent -> superTypes.add(parent.getNameWithScope()));
        } else if (type instanceof RecordDeclaration declaration) {
            declaration.getImplementedTypes().forEach(parent -> superTypes.add(parent.getNameWithScope()));
        }
        return new TypeInfo(qualifiedName, simpleName(qualifiedName), interfaceType, Set.copyOf(superTypes));
    }

    private static String qualifiedTypeName(String packageName, TypeDeclaration<?> type) {
        return type.getFullyQualifiedName().orElseGet(() -> {
            var names = new ArrayList<String>();
            Node current = type;
            while (current instanceof TypeDeclaration<?> declaration) {
                names.add(declaration.getNameAsString());
                current = declaration.getParentNode().orElse(null);
            }
            Collections.reverse(names);
            String localName = String.join(".", names);
            return packageName.isBlank() ? localName : packageName + '.' + localName;
        });
    }

    private static List<AnnotationDescriptor> annotations(NodeList<AnnotationExpr> annotations) {
        var result = new ArrayList<AnnotationDescriptor>(annotations.size());
        for (var annotation : annotations) {
            var attributes = new TreeMap<String, String>();
            if (annotation instanceof SingleMemberAnnotationExpr single) {
                attributes.put("value", annotationValue(single.getMemberValue()));
            } else if (annotation instanceof NormalAnnotationExpr normal) {
                normal.getPairs().forEach(pair ->
                        attributes.put(pair.getNameAsString(), annotationValue(pair.getValue())));
            }
            result.add(new AnnotationDescriptor(annotation.getName().getIdentifier(), attributes));
        }
        return List.copyOf(result);
    }

    private static String annotationValue(Expression expression) {
        if (expression instanceof StringLiteralExpr literal) return literal.asString();
        if (expression instanceof ArrayInitializerExpr array) {
            return array.getValues().stream().map(JavaParserProjectAnalyzer::annotationValue)
                    .reduce((left, right) -> left + ',' + right).orElse("");
        }
        return expression.toString();
    }

    private static String scope(MethodCallExpr call) {
        return call.getScope().map(Node::toString).orElse("");
    }

    private static SourceLocation location(Path root, Path file, Node node) {
        int start = node.getBegin().map(position -> position.line).orElse(1);
        int end = node.getEnd().map(position -> position.line).orElse(start);
        return new SourceLocation(root.relativize(file.toAbsolutePath().normalize()), start, end);
    }

    private static String simpleName(String type) {
        if (type == null || type.isBlank()) return "";
        String baseType = baseTypeName(type);
        int lastDot = baseType.lastIndexOf('.');
        return lastDot >= 0 ? baseType.substring(lastDot + 1) : baseType;
    }

    private static String baseTypeName(String type) {
        String normalized = type == null ? "" : type.trim();
        int generic = normalized.indexOf('<');
        if (generic >= 0) normalized = normalized.substring(0, generic);
        while (normalized.endsWith("[]")) normalized = normalized.substring(0, normalized.length() - 2);
        return normalized;
    }

    private static String receiverType(String scope, Map<String, String> variableTypes) {
        String direct = variableTypes.get(scope);
        if (direct != null) return direct;
        if (scope.startsWith("this.")) {
            String field = scope.substring("this.".length());
            if (!field.contains(".")) return variableTypes.get(field);
        }
        return null;
    }

    private record MappedUnit(Path file, List<RawMethod> rawMethods, List<TypeInfo> types) {}

    private record ParseBatch(List<MappedUnit> units, List<ParseFailure> failures) {}

    private record AnnotationDescriptor(String name, Map<String, String> attributes) {
        private AnnotationDescriptor {
            attributes = Collections.unmodifiableMap(new TreeMap<>(attributes));
        }
    }

    private record TypeInfo(String qualifiedName, String simpleName, boolean interfaceType, Set<String> superTypes) {}

    private record RawCall(SourceLocation location, String scope, String methodName, int argumentCount) {}

    private record RawMethod(
            MethodId id,
            SourceLocation location,
            Set<String> annotationNames,
            List<AnnotationDescriptor> typeAnnotations,
            List<AnnotationDescriptor> methodAnnotations,
            List<CatchEvidence> catches,
            List<InvocationEvidence> invocations,
            List<MetricTagEvidence> metricTags,
            List<RawCall> calls,
            Map<String, String> variableTypes
    ) {}

    private record MappedProject(
            Map<MethodId, RawMethod> rawMethods,
            Map<String, TypeInfo> types,
            List<ParseFailure> failures
    ) {}

    private record Resolution(Optional<MethodId> target, ResolutionReason reason) {}
}
