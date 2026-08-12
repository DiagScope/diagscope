package dev.diagscope.javaparser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
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
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisPolicy;
import dev.diagscope.core.application.port.out.ProjectAnalyzer;
import dev.diagscope.core.domain.AdviceKind;
import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.AspectAdvice;
import dev.diagscope.core.domain.MethodVisibility;
import dev.diagscope.core.domain.ProxyProfile;
import dev.diagscope.core.domain.ProjectLayout;
import dev.diagscope.core.domain.CatchEvidence;
import dev.diagscope.core.domain.CallableShape;
import dev.diagscope.core.domain.Entrypoint;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodCall;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.MetricNameEvidence;
import dev.diagscope.core.domain.MetricTagEvidence;
import dev.diagscope.core.domain.ParseFailure;
import dev.diagscope.core.domain.ResolutionReason;
import dev.diagscope.core.domain.SourceLocation;
import dev.diagscope.jvmanalysis.ProjectLayoutDetector;
import dev.diagscope.jvmanalysis.AspectPointcutMatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
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
    /** Standard JAX-RS method annotations, used by Quarkus REST resource classes. */
    private static final Set<String> JAX_RS_HTTP_METHOD_ANNOTATIONS = Set.of(
            "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"
    );
    private static final Set<String> SPRING_STEREOTYPES = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration",
            "ControllerAdvice", "RestControllerAdvice", "Aspect"
    );
    /** Annotations whose behaviour is delivered by a Spring proxy rather than by the method body. */
    private static final Set<String> PROXIED_ANNOTATIONS = Set.of(
            "Transactional", "Async", "Cacheable", "CacheEvict", "CachePut", "Caching",
            "Retryable", "CircuitBreaker", "RateLimiter", "Bulkhead", "TimeLimiter",
            "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "Validated",
            "Observed", "Timed", "Counted", "NewSpan", "ContinueSpan"
    );
    private static final Set<String> LOGGER_METHODS = Set.of("trace", "debug", "info", "warn", "error", "log");
    private static final Set<String> OBSERVING_COMPLETION_METHODS = Set.of(
            "get", "join", "whenComplete", "handle", "exceptionally", "thenAccept", "thenRun"
    );
    private static final Pattern STABLE_FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{2,}");
    private static final Pattern SILENT_CATCH_SUPPRESSION = Pattern.compile(
            "(?i)diagscope\\s*:\\s*ignore\\s+SILENT_CATCH\\s*--\\s*\\S.*"
    );

    @Override
    public AnalyzedProject analyze(Path projectDirectory, AnalysisOptions options) {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(options, "options");
        ProjectLayout layout = ProjectLayoutDetector.detect(projectDirectory, options.additionalSourceRoots());
        Path root = layout.root();

        List<Path> sourceFiles = discoverSourceFiles(layout.sourceRoots()).stream()
                .filter(file -> !options.policy().ignores(root.relativize(file.toAbsolutePath().normalize())))
                .toList();
        ParseBatch parseBatch = parseFiles(root, sourceFiles, layout.sourceRoots(),
                explicitClasspath(root, options.explicitClasspath()),
                options.parallelism(), options.policy());
        MappedProject mapped = mergeMappedUnits(parseBatch.units());
        List<AspectAdvice> aspects = collectAspects(mapped);
        Map<MethodId, MethodModel> methods = resolveLocalCalls(mapped, aspects);
        List<Entrypoint> entrypoints = detectEntrypoints(mapped,
                options.enabledEntrypointTypes(), options.policy());

        var failures = new ArrayList<ParseFailure>(parseBatch.failures().size() + mapped.failures().size());
        failures.addAll(parseBatch.failures());
        failures.addAll(mapped.failures());
        failures.sort(Comparator.comparing(failure -> failure.file().toString()));
        return new AnalyzedProject(root.getFileName().toString(), root, layout, methods, entrypoints,
                sourceFiles.size(), failures, aspects);
    }

    /** Collects the Java files of every source root, deduplicated so nested modules cannot double count. */
    private static List<Path> discoverSourceFiles(List<Path> sourceRoots) {
        var files = new java.util.TreeSet<Path>();
        for (Path sourceRoot : sourceRoots) {
            try (var stream = Files.find(sourceRoot, Integer.MAX_VALUE,
                    (path, attributes) -> attributes.isRegularFile() && path.toString().endsWith(".java"))) {
                stream.forEach(files::add);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to discover Java sources under " + sourceRoot, exception);
            }
        }
        return List.copyOf(files);
    }

    private static ParseBatch parseFiles(
            Path root,
            List<Path> files,
            List<Path> sourceRoots,
            List<Path> explicitClasspath,
            int parallelism,
            AnalysisPolicy policy
    ) {
        var units = new MappedUnit[files.size()];
        var failures = new ParseFailure[files.size()];
        ParserResources parserResources = parserResources(sourceRoots, explicitClasspath);
        ParserConfiguration configuration = parserResources.configuration();
        boolean symbolResolutionEnabled = !explicitClasspath.isEmpty();
        int workers = explicitClasspath.isEmpty() ? parallelism : 1;
        var pool = new ForkJoinPool(Math.min(workers, Math.max(1, files.size())));
        try {
            pool.submit(() -> IntStream.range(0, files.size()).parallel().forEach(index -> {
                Path file = files.get(index);
                Path relativeFile = root.relativize(file.toAbsolutePath().normalize());
                try {
                    var result = new JavaParser(configuration).parse(file);
                    result.getResult().ifPresent(unit -> units[index] = mapUnit(
                            root, file, unit, policy, symbolResolutionEnabled));
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
            parserResources.close();
        }
        return new ParseBatch(
                Arrays.stream(units).filter(Objects::nonNull).toList(),
                Arrays.stream(failures).filter(Objects::nonNull).toList()
        );
    }

    private static ParserResources parserResources(List<Path> sourceRoots, List<Path> explicitClasspath) {
        var configuration = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25);
        if (explicitClasspath.isEmpty()) return new ParserResources(configuration, null);

        var solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver(true));
        sourceRoots.stream().filter(Files::isDirectory).forEach(root -> solver.add(new JavaParserTypeSolver(root)));
        try {
            URL[] urls = explicitClasspath.stream().map(path -> {
                try {
                    return path.toUri().toURL();
                } catch (java.net.MalformedURLException exception) {
                    throw new IllegalArgumentException("Invalid classpath entry: " + path, exception);
                }
            }).toArray(URL[]::new);
            var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
            solver.add(new ClassLoaderTypeSolver(loader));
            configuration.setSymbolResolver(new JavaSymbolSolver(solver));
            return new ParserResources(configuration, loader);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Unable to configure explicit classpath: "
                    + exception.getMessage(), exception);
        }
    }

    private static List<Path> explicitClasspath(Path root, List<Path> configured) {
        var result = new LinkedHashSet<Path>();
        for (Path entry : configured) {
            Path resolved = entry.isAbsolute() ? entry.toAbsolutePath().normalize()
                    : root.resolve(entry).toAbsolutePath().normalize();
            if (!Files.exists(resolved)) {
                throw new IllegalArgumentException("Explicit classpath entry does not exist: " + resolved);
            }
            if (!Files.isDirectory(resolved) && !Files.isRegularFile(resolved)) {
                throw new IllegalArgumentException("Explicit classpath entry is not a file or directory: "
                        + resolved);
            }
            String name = resolved.getFileName().toString().toLowerCase(Locale.ROOT);
            if (Files.isRegularFile(resolved) && !name.endsWith(".jar") && !name.endsWith(".zip")) {
                throw new IllegalArgumentException("Explicit classpath entry must be a JAR/ZIP or directory: "
                        + resolved);
            }
            result.add(resolved);
        }
        return List.copyOf(result);
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

    private static MappedUnit mapUnit(
            Path root, Path file, CompilationUnit unit, AnalysisPolicy policy,
            boolean symbolResolutionEnabled) {
        var rawMethods = new ArrayList<RawMethod>();
        var types = new ArrayList<TypeInfo>();
        String packageName = unit.getPackageDeclaration().map(node -> node.getNameAsString()).orElse("");
        for (Node node : unit.findAll(Node.class, TypeDeclaration.class::isInstance)) {
            TypeDeclaration<?> type = (TypeDeclaration<?>) node;
            if (hasMethodAncestor(type)) continue;
            String qualifiedName = qualifiedTypeName(packageName, type);
            Map<String, String> declaredVariables = declaredTypeVariables(type);
            List<AnnotationDescriptor> typeAnnotations = annotations(type.getAnnotations());
            types.add(typeInfo(qualifiedName, type, typeAnnotations));

            for (BodyDeclaration<?> member : type.getMembers()) {
                if (!member.isMethodDeclaration()) continue;
                rawMethods.add(mapMethod(root, file, qualifiedName, member.asMethodDeclaration(),
                        declaredVariables, typeAnnotations, policy, symbolResolutionEnabled));
            }
        }
        return new MappedUnit(root.relativize(file.toAbsolutePath().normalize()),
                List.copyOf(rawMethods), List.copyOf(types), declaresProducerListener(unit));
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
        boolean producerListenerVisible = units.stream().anyMatch(MappedUnit::producerListenerVisible);
        return new MappedProject(
                Collections.unmodifiableMap(new LinkedHashMap<>(rawMethods)),
                Collections.unmodifiableMap(new LinkedHashMap<>(types)),
                List.copyOf(failures),
                producerListenerVisible
        );
    }

    private static RawMethod mapMethod(
            Path root,
            Path file,
            String declaringType,
            MethodDeclaration method,
            Map<String, String> declaredVariables,
            List<AnnotationDescriptor> typeAnnotations,
            AnalysisPolicy policy,
            boolean symbolResolutionEnabled
    ) {
        MethodId id = methodId(declaringType, method);
        SourceLocation methodLocation = location(root, file, method);
        List<AnnotationDescriptor> methodAnnotations = annotations(method.getAnnotations());

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
                .map(clause -> catchEvidence(root, file, clause, variableTypes, policy))
                .sorted(Comparator.comparingInt(evidence -> evidence.location().startLine()))
                .toList();

        var parameterNames = new LinkedHashSet<String>();
        method.getParameters().forEach(parameter -> parameterNames.add(parameter.getNameAsString()));
        var localNames = new LinkedHashSet<String>();
        method.findAll(VariableDeclarator.class).stream()
                .filter(variable -> belongsToMethod(variable, method))
                .forEach(variable -> localNames.add(variable.getNameAsString()));
        var fieldNames = new LinkedHashSet<>(declaredVariables.keySet());
        fieldNames.removeAll(parameterNames);
        fieldNames.removeAll(localNames);
        var constantFieldNames = new LinkedHashSet<String>();
        fieldNames.stream().filter(name -> name.equals(name.toUpperCase(Locale.ROOT)))
                .forEach(constantFieldNames::add);
        var metricNamesContext = new MetricEvidenceExtractor.Names(
                Collections.unmodifiableMap(new LinkedHashMap<>(variableTypes)),
                Set.copyOf(parameterNames), Set.copyOf(localNames),
                Set.copyOf(fieldNames), Set.copyOf(constantFieldNames));

        var invocations = new ArrayList<InvocationEvidence>();
        var metricTags = new ArrayList<MetricTagEvidence>();
        var metricNames = new ArrayList<MetricNameEvidence>();
        var calls = new ArrayList<RawCall>();
        List<MethodCallExpr> methodCalls = method.findAll(MethodCallExpr.class).stream()
                .filter(call -> belongsToMethod(call, method))
                .sorted(Comparator.comparingInt(call -> call.getBegin().map(position -> position.line).orElse(1)))
                .toList();
        for (var call : methodCalls) {
            InvocationEvidence invocation = invocationEvidence(
                    root, file, call, variableTypes, policy, symbolResolutionEnabled);
            invocations.add(invocation);
            SourceLocation callLocation = location(root, file, call);
            metricTags.addAll(MetricEvidenceExtractor.tags(callLocation, call, metricNamesContext));
            MetricEvidenceExtractor.meter(callLocation, call, metricNamesContext).ifPresent(metricNames::add);
            calls.add(new RawCall(location(root, file, call), scope(call), invocation.receiverType(),
                    call.getNameAsString(), call.getArguments().size(), invocation.argumentTypes()));
        }

        int varargIndex = method.getParameters().stream().filter(parameter -> parameter.isVarArgs())
                .findFirst().map(method.getParameters()::indexOf).orElse(-1);
        int minimumArity = varargIndex >= 0 ? method.getParameters().size() - 1
                : method.getParameters().size();
        int maximumArity = varargIndex >= 0 ? Integer.MAX_VALUE : method.getParameters().size();

        return new RawMethod(id, methodLocation, typeAnnotations, methodAnnotations,
                catches, List.copyOf(invocations), dedupeTags(metricTags), dedupeMeters(metricNames),
                List.copyOf(calls), Collections.unmodifiableMap(new LinkedHashMap<>(variableTypes)),
                visibility(method), method.isStatic(), method.isFinal(), method.getTypeAsString(),
                minimumArity, maximumArity, varargIndex,
                method.getTypeParameters().stream().map(parameter -> parameter.getNameAsString())
                        .collect(java.util.stream.Collectors.toSet()), method.getBody().isPresent());
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

    private static MethodVisibility visibility(MethodDeclaration method) {
        if (method.isPrivate()) return MethodVisibility.PRIVATE;
        if (method.isProtected()) return MethodVisibility.PROTECTED;
        if (method.isPublic()) return MethodVisibility.PUBLIC;
        return MethodVisibility.PACKAGE_PRIVATE;
    }

    private static Set<String> typeAnnotationNames(List<AnnotationDescriptor> typeAnnotations) {
        var names = new TreeSet<String>();
        typeAnnotations.forEach(annotation -> names.add(annotation.name()));
        return Collections.unmodifiableSet(names);
    }

    /**
     * Collects the advice declared by {@code @Aspect} classes. Advice is instrumentation that no
     * call site mentions, so surfacing the declarations is the only way a reader can know it exists.
     */
    private static List<AspectAdvice> collectAspects(MappedProject project) {
        var advice = new ArrayList<AspectAdvice>();
        for (var raw : project.rawMethods().values()) {
            List<AnnotationDescriptor> effectiveType = effectiveTypeAnnotations(raw, project);
            Set<String> typeAnnotations = typeAnnotationNames(effectiveType);
            if (!typeAnnotations.contains("Aspect")) continue;
            boolean springManagedAspect = typeAnnotations.stream()
                    .anyMatch(name -> SPRING_STEREOTYPES.contains(name) && !"Aspect".equals(name));
            for (var annotation : effectiveMethodAnnotations(raw, project)) {
                AdviceKind.fromAnnotation(annotation.name()).ifPresent(kind -> advice.add(new AspectAdvice(
                        raw.id().declaringType(), raw.id().name(), kind,
                        firstAttribute(annotation, "value", "pointcut").orElse(""),
                        raw.location(), springManagedAspect)));
            }
        }
        advice.sort(Comparator.comparing(AspectAdvice::id).thenComparing(item -> item.kind().name()));
        return List.copyOf(advice);
    }

    /** Types returned by a {@code @Bean} factory method, which registers them without a stereotype. */
    private static Set<String> beanFactoryTypes(MappedProject project) {
        var types = new TreeSet<String>();
        for (var raw : project.rawMethods().values()) {
            if (annotation(effectiveMethodAnnotations(raw, project), "Bean").isEmpty()) continue;
            types.add(simpleName(raw.returnType()));
        }
        return Collections.unmodifiableSet(types);
    }

    private static ProxyProfile proxyProfile(
            RawMethod raw,
            TypeInfo declaringType,
            List<AspectAdvice> aspects,
            Set<String> beanFactoryTypes,
            List<AnnotationDescriptor> effectiveTypeAnnotations,
            List<AnnotationDescriptor> effectiveMethodAnnotations
    ) {
        var annotationNames = new TreeSet<String>();
        effectiveMethodAnnotations.forEach(annotation -> annotationNames.add(annotation.name()));
        Set<String> typeAnnotations = typeAnnotationNames(effectiveTypeAnnotations);
        annotationNames.addAll(typeAnnotations);

        var proxiedAnnotations = new TreeSet<String>();
        annotationNames.stream().filter(PROXIED_ANNOTATIONS::contains).forEach(proxiedAnnotations::add);

        var target = new AspectPointcutMatcher.Target(
                raw.id().declaringType(), raw.id().name(), Collections.unmodifiableSet(annotationNames));
        var matchingAdvice = new ArrayList<String>();
        for (var candidate : aspects) {
            if (candidate.aspectType().equals(raw.id().declaringType())) continue;
            if (AspectPointcutMatcher.matches(candidate.pointcut(), target)) {
                matchingAdvice.add(candidate.displayName());
            }
        }

        boolean springManaged = effectiveTypeAnnotations.stream().map(AnnotationDescriptor::name)
                .anyMatch(SPRING_STEREOTYPES::contains);
        boolean finalType = declaringType != null && declaringType.finalType();
        boolean beanFactoryCandidate = beanFactoryTypes.contains(simpleName(raw.id().declaringType()));
        return new ProxyProfile(raw.visibility(), raw.staticMethod(), raw.finalMethod(), finalType,
                springManaged, beanFactoryCandidate, proxiedAnnotations, List.copyOf(matchingAdvice));
    }

    private static Map<MethodId, MethodModel> resolveLocalCalls(
            MappedProject project, List<AspectAdvice> aspects) {
        Set<String> beanFactoryTypes = beanFactoryTypes(project);
        var byQualifiedTypeAndName = new HashMap<String, List<RawMethod>>();
        var bySimpleTypeAndName = new HashMap<String, List<RawMethod>>();
        for (var method : project.rawMethods().values()) {
            MethodId id = method.id();
            byQualifiedTypeAndName.computeIfAbsent(id.declaringType() + '#' + id.name(), ignored -> new ArrayList<>())
                    .add(method);
            bySimpleTypeAndName.computeIfAbsent(simpleName(id.declaringType()) + '#' + id.name(), ignored -> new ArrayList<>())
                    .add(method);
        }
        byQualifiedTypeAndName.values().forEach(list ->
                list.sort(Comparator.comparing(method -> method.id().displayName())));
        bySimpleTypeAndName.values().forEach(list ->
                list.sort(Comparator.comparing(method -> method.id().displayName())));

        var typesBySimpleName = new HashMap<String, List<TypeInfo>>();
        project.types().values().forEach(type -> typesBySimpleName
                .computeIfAbsent(type.simpleName(), ignored -> new ArrayList<>()).add(type));
        typesBySimpleName.values().forEach(list -> list.sort(Comparator.comparing(TypeInfo::qualifiedName)));
        var implementationsBySuperType = new HashMap<String, List<TypeInfo>>();
        for (var type : project.types().values()) {
            if (type.interfaceType()) continue;
            for (String superType : allSuperTypes(type, project.types(), typesBySimpleName)) {
                implementationsBySuperType.computeIfAbsent(simpleName(superType), ignored -> new ArrayList<>())
                        .add(type);
            }
        }
        implementationsBySuperType.values().forEach(list ->
                list.sort(Comparator.comparing(TypeInfo::qualifiedName)));

        boolean producerListenerVisible = project.producerListenerVisible();
        var resolved = new LinkedHashMap<MethodId, MethodModel>(project.rawMethods().size());
        for (var raw : project.rawMethods().values()) {
            List<AnnotationDescriptor> typeAnnotations = effectiveTypeAnnotations(raw, project);
            List<AnnotationDescriptor> methodAnnotations = effectiveMethodAnnotations(raw, project);
            var effectiveNames = new TreeSet<String>();
            typeAnnotations.forEach(annotation -> effectiveNames.add(annotation.name()));
            methodAnnotations.forEach(annotation -> effectiveNames.add(annotation.name()));
            var calls = new ArrayList<MethodCall>(raw.calls().size());
            for (var call : raw.calls()) {
                Resolution resolution = resolveCall(raw, call, project.types(), typesBySimpleName,
                        implementationsBySuperType, byQualifiedTypeAndName, bySimpleTypeAndName);
                calls.add(new MethodCall(call.location(), call.scope(), call.methodName(), call.argumentCount(),
                        resolution.target(), resolution.reason()));
            }
            List<InvocationEvidence> invocations = producerListenerVisible
                    ? raw.invocations().stream()
                        .map(invocation -> invocation.withProducerListenerVisible(true)).toList()
                    : raw.invocations();
            invocations = enrichReceiverTypes(raw, invocations, project.types(), typesBySimpleName);
            resolved.put(raw.id(), new MethodModel(raw.id(), raw.location(), Set.copyOf(effectiveNames), raw.catches(),
                    invocations, raw.metricTags(), raw.metricNames(), calls,
                    proxyProfile(raw, project.types().get(raw.id().declaringType()), aspects, beanFactoryTypes,
                            typeAnnotations, methodAnnotations),
                    annotationAttributes(typeAnnotations, methodAnnotations),
                    new CallableShape(raw.minimumArity(), raw.maximumArity(),
                            raw.varargIndex(), raw.typeParameters())));
        }
        return Collections.unmodifiableMap(resolved);
    }

    private static List<InvocationEvidence> enrichReceiverTypes(
            RawMethod method,
            List<InvocationEvidence> invocations,
            Map<String, TypeInfo> types,
            Map<String, List<TypeInfo>> typesBySimpleName
    ) {
        return invocations.stream().map(invocation -> {
            if (!invocation.receiverType().isBlank()) return invocation;
            RawCall call = method.calls().stream()
                    .filter(candidate -> candidate.location().equals(invocation.location()))
                    .filter(candidate -> candidate.methodName().equals(invocation.methodName()))
                    .findFirst().orElse(null);
            if (call == null) return invocation;
            String receiver = resolveScopedReceiverType(method, call.scope(), types, typesBySimpleName);
            return receiver.isBlank() ? invocation : invocation.withReceiverType(receiver);
        }).toList();
    }

    /**
     * Flattens the annotation attributes that apply to a method. Type-level declarations are the
     * defaults; a method-level annotation of the same name overrides them, exactly like Spring
     * resolves {@code @Transactional}.
     */
    private static Map<String, Map<String, String>> annotationAttributes(
            List<AnnotationDescriptor> typeAnnotations,
            List<AnnotationDescriptor> methodAnnotations
    ) {
        var attributes = new LinkedHashMap<String, Map<String, String>>();
        for (var annotation : typeAnnotations) {
            if (annotation.attributes().isEmpty()) continue;
            attributes.put(annotation.name(), annotation.attributes());
        }
        for (var annotation : methodAnnotations) {
            if (annotation.attributes().isEmpty()) continue;
            attributes.put(annotation.name(), annotation.attributes());
        }
        return Collections.unmodifiableMap(attributes);
    }

    private static List<AnnotationDescriptor> effectiveTypeAnnotations(
            RawMethod method, MappedProject project) {
        TypeInfo owner = project.types().get(method.id().declaringType());
        if (owner == null) return expandAnnotations(method.typeAnnotations(), project.types());
        var result = new LinkedHashMap<String, AnnotationDescriptor>();
        collectTypeAnnotations(owner, project.types(), result, new LinkedHashSet<>());
        return List.copyOf(result.values());
    }

    private static void collectTypeAnnotations(
            TypeInfo type,
            Map<String, TypeInfo> types,
            Map<String, AnnotationDescriptor> result,
            Set<String> visited
    ) {
        if (!visited.add(type.qualifiedName())) return;
        expandAnnotations(type.declaredAnnotations(), types).forEach(annotation ->
                result.putIfAbsent(annotation.name(), annotation));
        type.superTypes().stream().sorted().forEach(superType -> {
            TypeInfo parent = findType(superType, types);
            if (parent != null) collectTypeAnnotations(parent, types, result, visited);
        });
    }

    private static List<AnnotationDescriptor> effectiveMethodAnnotations(
            RawMethod method, MappedProject project) {
        var result = new LinkedHashMap<String, AnnotationDescriptor>();
        expandAnnotations(method.methodAnnotations(), project.types()).forEach(annotation ->
                result.putIfAbsent(annotation.name(), annotation));
        TypeInfo owner = project.types().get(method.id().declaringType());
        if (owner != null) {
            collectInheritedMethodAnnotations(method, owner, project, result, new LinkedHashSet<>());
        }
        return List.copyOf(result.values());
    }

    private static void collectInheritedMethodAnnotations(
            RawMethod method,
            TypeInfo type,
            MappedProject project,
            Map<String, AnnotationDescriptor> result,
            Set<String> visited
    ) {
        if (!visited.add(type.qualifiedName())) return;
        for (String superType : type.superTypes().stream().sorted().toList()) {
            TypeInfo parent = findType(superType, project.types());
            if (parent == null) continue;
            project.rawMethods().values().stream()
                    .filter(candidate -> candidate.id().declaringType().equals(parent.qualifiedName()))
                    .filter(candidate -> sameSignature(method, candidate))
                    .flatMap(candidate -> expandAnnotations(candidate.methodAnnotations(), project.types()).stream())
                    .forEach(annotation -> result.putIfAbsent(annotation.name(), annotation));
            collectInheritedMethodAnnotations(method, parent, project, result, visited);
        }
    }

    private static boolean sameSignature(RawMethod left, RawMethod right) {
        if (!left.id().name().equals(right.id().name())) return false;
        if (left.id().parameterTypes().size() != right.id().parameterTypes().size()) return false;
        for (int index = 0; index < left.id().parameterTypes().size(); index++) {
            if (!simpleName(left.id().parameterTypes().get(index))
                    .equals(simpleName(right.id().parameterTypes().get(index)))) return false;
        }
        return true;
    }

    private static List<AnnotationDescriptor> expandAnnotations(
            List<AnnotationDescriptor> annotations, Map<String, TypeInfo> types) {
        var result = new LinkedHashMap<String, AnnotationDescriptor>();
        collectExpandedAnnotations(annotations, types, result, new LinkedHashSet<>());
        return List.copyOf(result.values());
    }

    private static void collectExpandedAnnotations(
            List<AnnotationDescriptor> annotations,
            Map<String, TypeInfo> types,
            Map<String, AnnotationDescriptor> result,
            Set<String> visited
    ) {
        for (AnnotationDescriptor annotation : annotations) {
            result.putIfAbsent(annotation.name(), annotation);
            List<TypeInfo> matches = types.values().stream().filter(TypeInfo::annotationType)
                    .filter(type -> type.simpleName().equals(annotation.name())).limit(2).toList();
            TypeInfo annotationType = matches.size() == 1 ? matches.getFirst() : null;
            if (annotationType != null && visited.add(annotationType.qualifiedName())) {
                collectExpandedAnnotations(annotationType.declaredAnnotations(), types, result, visited);
            }
        }
    }

    private static TypeInfo findType(String typeName, Map<String, TypeInfo> types) {
        TypeInfo exact = types.get(baseTypeName(typeName));
        if (exact != null) return exact;
        List<TypeInfo> candidates = types.values().stream()
                .filter(type -> type.simpleName().equals(simpleName(typeName))).limit(2).toList();
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static Resolution resolveCall(
            RawMethod raw,
            RawCall call,
            Map<String, TypeInfo> types,
            Map<String, List<TypeInfo>> typesBySimpleName,
            Map<String, List<TypeInfo>> implementationsBySuperType,
            Map<String, List<RawMethod>> byQualifiedTypeAndName,
            Map<String, List<RawMethod>> bySimpleTypeAndName
    ) {
        String scope = call.scope();
        if (scope.isBlank() || "this".equals(scope)) {
            Resolution direct = choose(
                    byQualifiedTypeAndName.get(raw.id().declaringType() + '#' + call.methodName()),
                    call, ResolutionReason.SAME_CLASS);
            if (direct.target().isPresent() || direct.reason() == ResolutionReason.AMBIGUOUS) return direct;
            TypeInfo owner = types.get(raw.id().declaringType());
            return choose(owner == null ? List.of() : hierarchyMethods(owner, call.methodName(), types,
                            typesBySimpleName, byQualifiedTypeAndName, new LinkedHashSet<>(), true),
                    call, ResolutionReason.DECLARED_RECEIVER);
        }
        if ("super".equals(scope)) {
            TypeInfo owner = types.get(raw.id().declaringType());
            return choose(owner == null ? List.of() : hierarchyMethods(owner, call.methodName(), types,
                    typesBySimpleName, byQualifiedTypeAndName, new LinkedHashSet<>(), true),
                    call, ResolutionReason.DECLARED_RECEIVER);
        }

        String declaredType = call.receiverType().isBlank()
                ? resolveScopedReceiverType(raw, scope, types, typesBySimpleName)
                : call.receiverType();
        if (!declaredType.isBlank()) {
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
                        call, ResolutionReason.DECLARED_RECEIVER);
                if (direct.target().isPresent() || direct.reason() == ResolutionReason.AMBIGUOUS) return direct;
                return choose(hierarchyMethods(receiverInfo, call.methodName(), types, typesBySimpleName,
                                byQualifiedTypeAndName, new LinkedHashSet<>(), false),
                        call, ResolutionReason.DECLARED_RECEIVER);
            }
            if (receiverInfo == null) {
                return new Resolution(Optional.empty(), ResolutionReason.EXTERNAL);
            }

            var implementationCandidates = new LinkedHashMap<MethodId, RawMethod>();
            for (var type : implementationsBySuperType.getOrDefault(receiver, List.of())) {
                hierarchyMethods(type, call.methodName(), types, typesBySimpleName,
                        byQualifiedTypeAndName, new LinkedHashSet<>(), false).stream()
                        .filter(candidate -> candidate.acceptsArity(call.argumentCount()))
                        .forEach(candidate -> implementationCandidates.putIfAbsent(candidate.id(), candidate));
            }
            if (implementationCandidates.size() == 1) {
                return new Resolution(Optional.of(implementationCandidates.keySet().iterator().next()),
                        ResolutionReason.SINGLE_IMPLEMENTATION);
            }
            if (implementationCandidates.size() > 1) {
                List<RawMethod> typed = bestTypedMatches(List.copyOf(implementationCandidates.values()),
                        call.argumentTypes());
                if (typed.size() == 1) return new Resolution(Optional.of(typed.getFirst().id()),
                        ResolutionReason.SINGLE_IMPLEMENTATION);
                return new Resolution(Optional.empty(), ResolutionReason.AMBIGUOUS);
            }
            return choose(hierarchyMethods(receiverInfo, call.methodName(), types, typesBySimpleName,
                            byQualifiedTypeAndName, new LinkedHashSet<>(), false),
                    call, ResolutionReason.DECLARED_RECEIVER);
        }

        String possibleType = simpleName(scope);
        if (typesBySimpleName.containsKey(possibleType)) {
            return choose(bySimpleTypeAndName.get(possibleType + '#' + call.methodName()),
                    call, ResolutionReason.DECLARED_RECEIVER);
        }
        if (looksExternal(scope)) {
            return new Resolution(Optional.empty(), ResolutionReason.EXTERNAL);
        }
        return new Resolution(Optional.empty(), ResolutionReason.UNRESOLVED);
    }

    private static Resolution choose(List<RawMethod> candidates, RawCall call, ResolutionReason successReason) {
        if (candidates == null) return new Resolution(Optional.empty(), ResolutionReason.UNRESOLVED);
        List<RawMethod> matches = candidates.stream().filter(RawMethod::executableBody)
                .filter(candidate -> candidate.acceptsArity(call.argumentCount())).toList();
        if (matches.size() == 1) return new Resolution(Optional.of(matches.getFirst().id()), successReason);
        if (matches.size() > 1) {
            List<RawMethod> typed = bestTypedMatches(matches, call.argumentTypes());
            if (typed.size() == 1) return new Resolution(Optional.of(typed.getFirst().id()), successReason);
            return new Resolution(Optional.empty(), ResolutionReason.AMBIGUOUS);
        }
        return new Resolution(Optional.empty(), ResolutionReason.UNRESOLVED);
    }

    private static List<RawMethod> hierarchyMethods(
            TypeInfo type,
            String methodName,
            Map<String, TypeInfo> types,
            Map<String, List<TypeInfo>> typesBySimpleName,
            Map<String, List<RawMethod>> byTypeAndName,
            Set<String> visited,
            boolean skipDeclared
    ) {
        if (!visited.add(type.qualifiedName())) return List.of();
        if (!skipDeclared) {
            List<RawMethod> declared = byTypeAndName.getOrDefault(
                            type.qualifiedName() + '#' + methodName, List.of()).stream()
                    .filter(RawMethod::executableBody).toList();
            if (!declared.isEmpty()) return declared;
        }
        var inherited = new LinkedHashMap<MethodId, RawMethod>();
        type.superTypes().stream().sorted().forEach(superType -> {
            TypeInfo parent = findType(superType, types, typesBySimpleName);
            if (parent == null) return;
            hierarchyMethods(parent, methodName, types, typesBySimpleName, byTypeAndName,
                    visited, false).forEach(method -> inherited.putIfAbsent(method.id(), method));
        });
        return List.copyOf(inherited.values());
    }

    private static Set<String> allSuperTypes(
            TypeInfo type, Map<String, TypeInfo> types, Map<String, List<TypeInfo>> typesBySimpleName) {
        var result = new TreeSet<String>();
        collectSuperTypes(type, types, typesBySimpleName, result, new LinkedHashSet<>());
        return Collections.unmodifiableSet(result);
    }

    private static void collectSuperTypes(
            TypeInfo type,
            Map<String, TypeInfo> types,
            Map<String, List<TypeInfo>> typesBySimpleName,
            Set<String> result,
            Set<String> visited
    ) {
        if (!visited.add(type.qualifiedName())) return;
        for (String superType : type.superTypes()) {
            result.add(superType);
            TypeInfo parent = findType(superType, types, typesBySimpleName);
            if (parent != null) collectSuperTypes(parent, types, typesBySimpleName, result, visited);
        }
    }

    private static TypeInfo findType(
            String typeName, Map<String, TypeInfo> types, Map<String, List<TypeInfo>> typesBySimpleName) {
        TypeInfo exact = types.get(baseTypeName(typeName));
        if (exact != null) return exact;
        List<TypeInfo> candidates = typesBySimpleName.getOrDefault(simpleName(typeName), List.of());
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static String resolveScopedReceiverType(
            RawMethod caller,
            String scope,
            Map<String, TypeInfo> types,
            Map<String, List<TypeInfo>> typesBySimpleName
    ) {
        if (scope == null || !scope.matches("(?:this\\.)?[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) {
            return "";
        }
        String[] parts = scope.split("\\.");
        int index;
        String currentType;
        if ("this".equals(parts[0])) {
            currentType = caller.id().declaringType();
            index = 1;
        } else {
            currentType = caller.variableTypes().get(parts[0]);
            index = 1;
        }
        if (currentType == null || currentType.isBlank()) return "";
        while (index < parts.length) {
            TypeInfo info = findType(currentType, types, typesBySimpleName);
            if (info == null) return "";
            currentType = info.memberTypes().get(parts[index++]);
            if (currentType == null || currentType.isBlank()) return "";
        }
        return currentType;
    }

    private static List<RawMethod> bestTypedMatches(List<RawMethod> candidates, List<String> argumentTypes) {
        int bestScore = Integer.MIN_VALUE;
        var best = new ArrayList<RawMethod>();
        for (RawMethod candidate : candidates) {
            int score = compatibilityScore(candidate, argumentTypes);
            if (score < 0) continue;
            if (score > bestScore) {
                bestScore = score;
                best.clear();
            }
            if (score == bestScore) best.add(candidate);
        }
        return List.copyOf(best);
    }

    private static int compatibilityScore(RawMethod candidate, List<String> argumentTypes) {
        int score = 0;
        for (int index = 0; index < argumentTypes.size(); index++) {
            String argument = normalizedJvmType(argumentTypes.get(index));
            if (argument.isBlank() || "null".equals(argument)) continue;
            int parameterIndex = candidate.varargIndex() >= 0 && index >= candidate.varargIndex()
                    ? candidate.varargIndex() : index;
            if (parameterIndex >= candidate.id().parameterTypes().size()) return -1;
            String parameter = normalizedJvmType(candidate.id().parameterTypes().get(parameterIndex));
            if (parameter.isBlank() || "Any".equals(parameter) || "Object".equals(parameter)
                    || candidate.typeParameters().contains(parameter)) continue;
            if (parameter.equals(argument)) score += 3;
            else if (isNumericType(parameter) && isNumericType(argument)) score += 1;
            else return -1;
        }
        return score;
    }

    private static boolean isNumericType(String type) {
        return Set.of("Byte", "Short", "Int", "Long", "Float", "Double").contains(type);
    }

    private static String normalizedJvmType(String type) {
        String normalized = simpleName(type).replace("...", "").trim();
        return switch (normalized) {
            case "Integer", "int" -> "Int";
            case "long" -> "Long";
            case "boolean" -> "Boolean";
            case "double" -> "Double";
            case "float" -> "Float";
            case "short" -> "Short";
            case "byte" -> "Byte";
            case "char", "Character" -> "Char";
            default -> normalized;
        };
    }

    private static boolean looksExternal(String scope) {
        if (scope.startsWith("System.") || scope.startsWith("java.")) return true;
        int separator = scope.indexOf('.');
        String root = separator < 0 ? scope : scope.substring(0, separator);
        return !root.isBlank() && Character.isUpperCase(root.charAt(0));
    }

    private static List<Entrypoint> detectEntrypoints(
            MappedProject project,
            Set<EntrypointType> enabledTypes,
            AnalysisPolicy policy
    ) {
        var result = new ArrayList<Entrypoint>();
        for (var method : project.rawMethods().values()) {
            if (!method.executableBody()) continue;
            List<AnnotationDescriptor> typeAnnotations = effectiveTypeAnnotations(method, project);
            List<AnnotationDescriptor> methodAnnotations = effectiveMethodAnnotations(method, project);
            if (enabledTypes.contains(EntrypointType.REST)) {
                detectRestEntrypoint(method, typeAnnotations, methodAnnotations).ifPresent(result::add);
            }
            if (enabledTypes.contains(EntrypointType.KAFKA_LISTENER)) {
                detectKafkaEntrypoint(method, typeAnnotations, methodAnnotations).ifPresent(result::add);
            }
            if (enabledTypes.contains(EntrypointType.REACTIVE_MESSAGE)) {
                annotation(methodAnnotations, "Incoming").ifPresent(annotation -> result.add(new Entrypoint(
                        EntrypointType.REACTIVE_MESSAGE, method.id(), reactiveMessageDisplay(annotation),
                        method.location())));
            }
            if (enabledTypes.contains(EntrypointType.SCHEDULED)) {
                annotation(methodAnnotations, "Scheduled").ifPresent(annotation -> {
                    String schedule = scheduleDisplay(annotation);
                    result.add(new Entrypoint(EntrypointType.SCHEDULED, method.id(), schedule, method.location()));
                });
            }
            addCustomEntrypoints(result, method, methodAnnotations, enabledTypes, policy);
        }
        result.sort(Comparator.comparing((Entrypoint entrypoint) -> entrypoint.type().name())
                .thenComparing(Entrypoint::displayName)
                .thenComparing(entrypoint -> entrypoint.method().displayName()));
        return List.copyOf(result);
    }

    private static void addCustomEntrypoints(
            List<Entrypoint> result,
            RawMethod method,
            List<AnnotationDescriptor> effectiveMethodAnnotations,
            Set<EntrypointType> enabledTypes,
            AnalysisPolicy policy
    ) {
        Set<String> methodAnnotations = effectiveMethodAnnotations.stream()
                .map(AnnotationDescriptor::name).collect(java.util.stream.Collectors.toSet());
        for (EntrypointType type : enabledTypes) {
            if (result.stream().anyMatch(entrypoint -> entrypoint.type() == type
                    && entrypoint.method().equals(method.id()))) continue;
            policy.customEntrypointAnnotations(type).stream()
                    .filter(methodAnnotations::contains)
                    .findFirst()
                    .ifPresent(annotation -> result.add(new Entrypoint(type, method.id(),
                            "Custom " + type + " @" + annotation, method.location())));
        }
    }

    /**
     * Recognises every syntax-visible Kafka listener shape: a method level {@code @KafkaListener},
     * a class level {@code @KafkaListener} whose payload methods carry {@code @KafkaHandler}, and
     * the topic selectors ({@code topics}, {@code topicPattern}, {@code topicPartitions}).
     */
    private static Optional<Entrypoint> detectKafkaEntrypoint(
            RawMethod method,
            List<AnnotationDescriptor> typeAnnotations,
            List<AnnotationDescriptor> methodAnnotations
    ) {
        Optional<AnnotationDescriptor> methodLevel = annotation(methodAnnotations, "KafkaListener");
        if (methodLevel.isPresent()) {
            return Optional.of(new Entrypoint(EntrypointType.KAFKA_LISTENER, method.id(),
                    kafkaDisplay(methodLevel.orElseThrow()), method.location()));
        }
        boolean classListener = annotation(typeAnnotations, "KafkaListener").isPresent();
        boolean handler = annotation(methodAnnotations, "KafkaHandler").isPresent()
                || annotation(methodAnnotations, "DltHandler").isPresent();
        if (!classListener || !handler) {
            return Optional.empty();
        }
        AnnotationDescriptor typeAnnotation = annotation(typeAnnotations, "KafkaListener").orElseThrow();
        String display = kafkaDisplay(typeAnnotation);
        if (annotation(methodAnnotations, "DltHandler").isPresent()) {
            display = display + " (DLT handler)";
        }
        return Optional.of(new Entrypoint(EntrypointType.KAFKA_LISTENER, method.id(), display, method.location()));
    }

    private static String kafkaDisplay(AnnotationDescriptor annotation) {
        Optional<String> topics = firstAttribute(annotation, "topics", "value");
        if (topics.isPresent()) {
            return "Kafka topic=" + topics.orElseThrow();
        }
        Optional<String> pattern = firstAttribute(annotation, "topicPattern");
        if (pattern.isPresent()) {
            return "Kafka topicPattern=" + pattern.orElseThrow();
        }
        Optional<String> partitions = firstAttribute(annotation, "topicPartitions");
        if (partitions.isPresent()) {
            return "Kafka topicPartitions=" + partitions.orElseThrow();
        }
        return "Kafka topic=unknown";
    }

    /** `@Incoming` names a logical channel; its connector is deliberately not guessed as Kafka. */
    private static String reactiveMessageDisplay(AnnotationDescriptor annotation) {
        return firstAttribute(annotation, "value", "channel")
                .map(channel -> "Reactive message channel=" + channel)
                .orElse("Reactive message channel=unknown");
    }

    private static Optional<Entrypoint> detectRestEntrypoint(
            RawMethod method,
            List<AnnotationDescriptor> typeAnnotations,
            List<AnnotationDescriptor> methodAnnotations
    ) {
        boolean controller = annotation(typeAnnotations, "RestController").isPresent()
                || annotation(typeAnnotations, "Controller").isPresent();
        Optional<AnnotationDescriptor> mapping = methodAnnotations.stream()
                .filter(annotation -> REST_MAPPING_ANNOTATIONS.contains(annotation.name()))
                .findFirst();
        if (controller && mapping.isPresent()) {
            String prefix = annotation(typeAnnotations, "RequestMapping")
                    .flatMap(annotation -> firstAttribute(annotation, "path", "value"))
                    .orElse("");
            String methodPath = firstAttribute(mapping.orElseThrow(), "path", "value").orElse("");
            String path = combinePaths(prefix, methodPath);
            String verb = restVerb(mapping.orElseThrow());
            return Optional.of(new Entrypoint(EntrypointType.REST, method.id(),
                    verb + ' ' + path, method.location()));
        }

        return detectJaxRsRestEntrypoint(method, typeAnnotations, methodAnnotations);
    }

    /**
     * Recognises JAX-RS resources used by Quarkus REST. A resource class must carry {@code @Path}
     * and an exposed method must carry one of the standard HTTP method annotations. A bare
     * {@code @Path} method only contributes a URI segment and is not an entrypoint by itself.
     */
    private static Optional<Entrypoint> detectJaxRsRestEntrypoint(
            RawMethod method,
            List<AnnotationDescriptor> typeAnnotations,
            List<AnnotationDescriptor> methodAnnotations
    ) {
        Optional<AnnotationDescriptor> resourcePath = annotation(typeAnnotations, "Path");
        if (resourcePath.isEmpty()) return Optional.empty();
        Optional<AnnotationDescriptor> httpMethod = methodAnnotations.stream()
                .filter(annotation -> JAX_RS_HTTP_METHOD_ANNOTATIONS.contains(annotation.name()))
                .findFirst();
        if (httpMethod.isEmpty()) return Optional.empty();

        String prefix = firstAttribute(resourcePath.orElseThrow(), "value", "path").orElse("");
        String methodPath = annotation(methodAnnotations, "Path")
                .flatMap(annotation -> firstAttribute(annotation, "value", "path"))
                .orElse("");
        return Optional.of(new Entrypoint(EntrypointType.REST, method.id(),
                httpMethod.orElseThrow().name() + ' ' + combinePaths(prefix, methodPath), method.location()));
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
        var details = new ArrayList<String>();
        for (String attribute : List.of("cron", "every", "fixedRateString", "fixedRate", "fixedDelayString",
                "fixedDelay", "delay", "delayed", "identity")) {
            String value = annotation.attributes().get(attribute);
            if (value != null && !value.isBlank()) details.add(attribute + '=' + value);
        }
        return details.isEmpty() ? "Scheduled" : "Scheduled " + String.join(", ", details);
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
            Map<String, String> variableTypes,
            AnalysisPolicy policy
    ) {
        var body = clause.getBody();
        boolean hasLog = body.findAll(MethodCallExpr.class).stream()
                .anyMatch(call -> isLoggerCall(call, variableTypes, policy));
        boolean hasThrow = !body.findAll(ThrowStmt.class).isEmpty();
        List<ReturnStmt> returns = body.findAll(ReturnStmt.class);
        Optional<Expression> returned = returns.stream().findFirst().flatMap(ReturnStmt::getExpression);
        String exceptionVariable = clause.getParameter().getNameAsString();
        boolean preservesCause = returned.stream().anyMatch(expression -> expression.findAll(NameExpr.class).stream()
                .anyMatch(name -> exceptionVariable.equals(name.getNameAsString())))
                || body.findAll(ThrowStmt.class).stream()
                        .anyMatch(statement -> statement.getExpression().findAll(NameExpr.class).stream()
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

    private static boolean isLoggerCall(
            MethodCallExpr call,
            Map<String, String> variableTypes,
            AnalysisPolicy policy
    ) {
        if (!LOGGER_METHODS.contains(call.getNameAsString())) return false;
        String rawScope = scope(call);
        String receiverType = Optional.ofNullable(receiverType(rawScope, variableTypes)).orElse("");
        String hint = (rawScope + ' ' + receiverType).toLowerCase(Locale.ROOT);
        return hint.contains("logger") || hint.matches(".*\\blog\\b.*")
                || policy.isCustomLogger(rawScope, receiverType);
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
            Map<String, String> variableTypes,
            AnalysisPolicy policy,
            boolean symbolResolutionEnabled
    ) {
        String rawScope = scope(call);
        String receiverType = symbolResolutionEnabled ? resolvedReceiverType(call) : "";
        if (receiverType.isBlank()) {
            receiverType = Optional.ofNullable(receiverType(rawScope, variableTypes))
                    .orElseGet(() -> inferReceiverType(rawScope));
        }
        return new InvocationEvidence(
                location(root, file, call),
                rawScope,
                receiverType,
                call.getNameAsString(),
                call.getArguments().stream().map(Node::toString).toList(),
                resultUsage(call),
                false,
                isTryResource(call),
                assignedVariable(call),
                insideFinally(call),
                insideLoop(call),
                isLoggerCall(call, variableTypes, policy),
                argumentTypes(call, variableTypes, symbolResolutionEnabled)
        );
    }

    /** Conservative source-only argument types used by the cross-language linker. */
    private static List<String> argumentTypes(
            MethodCallExpr call, Map<String, String> variableTypes, boolean symbolResolutionEnabled) {
        return call.getArguments().stream()
                .map(argument -> argumentType(argument, variableTypes, symbolResolutionEnabled)).toList();
    }

    private static String argumentType(
            Expression expression, Map<String, String> variableTypes, boolean symbolResolutionEnabled) {
        if (symbolResolutionEnabled) {
            try {
                String resolved = expression.calculateResolvedType().describe();
                if (!resolved.isBlank()) return resolved;
            } catch (RuntimeException ignored) {
                // An incomplete explicit classpath retains the deterministic syntax fallback below.
            }
        }
        if (expression.isNameExpr()) {
            return Objects.requireNonNullElse(variableTypes.get(expression.asNameExpr().getNameAsString()), "");
        }
        if (expression.isStringLiteralExpr() || expression.isTextBlockLiteralExpr()) return "String";
        if (expression.isIntegerLiteralExpr()) return "int";
        if (expression.isLongLiteralExpr()) return "long";
        if (expression.isDoubleLiteralExpr()) {
            String value = expression.asDoubleLiteralExpr().getValue();
            return value.endsWith("f") || value.endsWith("F") ? "float" : "double";
        }
        if (expression.isBooleanLiteralExpr()) return "boolean";
        if (expression.isCharLiteralExpr()) return "char";
        if (expression.isNullLiteralExpr()) return "null";
        if (expression.isObjectCreationExpr()) return expression.asObjectCreationExpr().getTypeAsString();
        if (expression.isCastExpr()) return expression.asCastExpr().getTypeAsString();
        return "";
    }

    private static String inferReceiverType(String rawScope) {
        if (rawScope.isBlank()) return "";
        if (rawScope.startsWith("System.")) return rawScope;
        int separator = rawScope.indexOf('.');
        String root = separator < 0 ? rawScope : rawScope.substring(0, separator);
        return !root.isBlank() && Character.isUpperCase(root.charAt(0)) ? root : "";
    }

    /** True when the call initialises a try-with-resources resource, which guarantees closing. */
    private static boolean isTryResource(MethodCallExpr call) {
        Node current = call;
        while (current.getParentNode().isPresent()) {
            Node parent = current.getParentNode().orElseThrow();
            if (parent instanceof com.github.javaparser.ast.stmt.TryStmt tryStmt) {
                for (Expression resource : tryStmt.getResources()) {
                    if (resource == current || resource.isAncestorOf(call)) {
                        return true;
                    }
                }
                return false;
            }
            current = parent;
        }
        return false;
    }

    /** True when the call runs from a {@code finally} block, so the failure path executes it too. */
    private static boolean insideFinally(MethodCallExpr call) {
        Node current = call;
        while (current.getParentNode().isPresent()) {
            Node parent = current.getParentNode().orElseThrow();
            if (parent instanceof com.github.javaparser.ast.stmt.TryStmt tryStmt
                    && tryStmt.getFinallyBlock().map(block -> block.isAncestorOf(call)).orElse(false)) {
                return true;
            }
            current = parent;
        }
        return false;
    }

    /** True when the call runs inside a for/while/do-while body, so it repeats per iteration. */
    private static boolean insideLoop(MethodCallExpr call) {
        Node current = call;
        while (current.getParentNode().isPresent()) {
            Node parent = current.getParentNode().orElseThrow();
            if (parent instanceof com.github.javaparser.ast.stmt.ForStmt
                    || parent instanceof com.github.javaparser.ast.stmt.ForEachStmt
                    || parent instanceof com.github.javaparser.ast.stmt.WhileStmt
                    || parent instanceof com.github.javaparser.ast.stmt.DoStmt) {
                return true;
            }
            if (parent instanceof com.github.javaparser.ast.body.MethodDeclaration) {
                return false;
            }
            current = parent;
        }
        return false;
    }

    /** Name of the variable the call result is assigned to, empty when the result is not assigned. */
    private static String assignedVariable(MethodCallExpr call) {
        Node current = call;
        while (current.getParentNode().isPresent()) {
            Node parent = current.getParentNode().orElseThrow();
            if (parent instanceof VariableDeclarator variable
                    && variable.getInitializer().orElse(null) == current) {
                return variable.getNameAsString();
            }
            if (parent instanceof AssignExpr assignment && assignment.getValue() == current) {
                return assignment.getTarget().toString();
            }
            if (parent instanceof Expression) {
                current = parent;
                continue;
            }
            return "";
        }
        return "";
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

    private static TypeInfo typeInfo(
            String qualifiedName, TypeDeclaration<?> type, List<AnnotationDescriptor> typeAnnotations) {
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
        boolean finalType = type.getModifiers().stream()
                .anyMatch(modifier -> modifier.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.FINAL);
        return new TypeInfo(qualifiedName, simpleName(qualifiedName), interfaceType, Set.copyOf(superTypes),
                List.copyOf(typeAnnotations), type instanceof AnnotationDeclaration,
                Map.copyOf(declaredTypeVariables(type)), finalType);
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

    private static String resolvedReceiverType(MethodCallExpr call) {
        if (call.getScope().isEmpty()) return "";
        try {
            return call.getScope().orElseThrow().calculateResolvedType().describe();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /** Detects a syntax-visible ProducerListener declaration or registration in this compilation unit. */
    private static boolean declaresProducerListener(CompilationUnit unit) {
        boolean registered = unit.findAll(MethodCallExpr.class).stream()
                .map(MethodCallExpr::getNameAsString)
                .anyMatch("setProducerListener"::equals);
        if (registered) return true;
        return unit.findAll(com.github.javaparser.ast.type.ClassOrInterfaceType.class).stream()
                .map(com.github.javaparser.ast.type.ClassOrInterfaceType::getNameAsString)
                .anyMatch("ProducerListener"::equals);
    }

    private static List<MetricTagEvidence> dedupeTags(List<MetricTagEvidence> tags) {
        var unique = new LinkedHashMap<String, MetricTagEvidence>();
        for (var tag : tags) {
            unique.putIfAbsent(tag.location().startLine() + "|" + tag.tagName() + "|" + tag.valueExpression(), tag);
        }
        return List.copyOf(unique.values());
    }

    private static List<MetricNameEvidence> dedupeMeters(List<MetricNameEvidence> meters) {
        var unique = new LinkedHashMap<String, MetricNameEvidence>();
        for (var meter : meters) {
            unique.putIfAbsent(meter.location().startLine() + "|" + meter.meterType()
                    + "|" + meter.nameExpression(), meter);
        }
        return List.copyOf(unique.values());
    }

    private record MappedUnit(Path file, List<RawMethod> rawMethods, List<TypeInfo> types,
            boolean producerListenerVisible) {}

    private record ParseBatch(List<MappedUnit> units, List<ParseFailure> failures) {}

    private record ParserResources(ParserConfiguration configuration, URLClassLoader classLoader)
            implements AutoCloseable {
        @Override
        public void close() {
            if (classLoader == null) return;
            try {
                classLoader.close();
            } catch (IOException ignored) {
                // Parsing is complete; closing a dependency JAR must not discard analysis results.
            }
        }
    }

    private record AnnotationDescriptor(String name, Map<String, String> attributes) {
        private AnnotationDescriptor {
            attributes = Collections.unmodifiableMap(new TreeMap<>(attributes));
        }
    }

    private record TypeInfo(
            String qualifiedName,
            String simpleName,
            boolean interfaceType,
            Set<String> superTypes,
            List<AnnotationDescriptor> declaredAnnotations,
            boolean annotationType,
            Map<String, String> memberTypes,
            boolean finalType
    ) {}

    private record RawCall(SourceLocation location, String scope, String receiverType, String methodName,
            int argumentCount, List<String> argumentTypes) {
        private RawCall {
            argumentTypes = List.copyOf(argumentTypes);
        }
    }

    private record RawMethod(
            MethodId id,
            SourceLocation location,
            List<AnnotationDescriptor> typeAnnotations,
            List<AnnotationDescriptor> methodAnnotations,
            List<CatchEvidence> catches,
            List<InvocationEvidence> invocations,
            List<MetricTagEvidence> metricTags,
            List<MetricNameEvidence> metricNames,
            List<RawCall> calls,
            Map<String, String> variableTypes,
            MethodVisibility visibility,
            boolean staticMethod,
            boolean finalMethod,
            String returnType,
            int minimumArity,
            int maximumArity,
            int varargIndex,
            Set<String> typeParameters,
            boolean executableBody
    ) {
        private boolean acceptsArity(int arity) {
            return arity >= minimumArity && arity <= maximumArity;
        }
    }

    private record MappedProject(
            Map<MethodId, RawMethod> rawMethods,
            Map<String, TypeInfo> types,
            List<ParseFailure> failures,
            boolean producerListenerVisible
    ) {}

    private record Resolution(Optional<MethodId> target, ResolutionReason reason) {}
}
