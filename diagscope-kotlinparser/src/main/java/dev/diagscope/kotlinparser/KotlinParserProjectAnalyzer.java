package dev.diagscope.kotlinparser;

import dev.diagscope.core.application.AnalysisOptions;
import dev.diagscope.core.application.AnalysisPolicy;
import dev.diagscope.core.application.port.out.ProjectAnalyzer;
import dev.diagscope.core.domain.AdviceKind;
import dev.diagscope.core.domain.AnalyzedProject;
import dev.diagscope.core.domain.AspectAdvice;
import dev.diagscope.core.domain.CatchEvidence;
import dev.diagscope.core.domain.Entrypoint;
import dev.diagscope.core.domain.EntrypointType;
import dev.diagscope.core.domain.InvocationEvidence;
import dev.diagscope.core.domain.InvocationResultUsage;
import dev.diagscope.core.domain.MethodCall;
import dev.diagscope.core.domain.MethodId;
import dev.diagscope.core.domain.MethodModel;
import dev.diagscope.core.domain.MethodVisibility;
import dev.diagscope.core.domain.MetricNameEvidence;
import dev.diagscope.core.domain.MetricTagEvidence;
import dev.diagscope.core.domain.ParseFailure;
import dev.diagscope.core.domain.ProjectLayout;
import dev.diagscope.core.domain.ProxyProfile;
import dev.diagscope.core.domain.ResolutionReason;
import dev.diagscope.core.domain.SourceLocation;
import dev.diagscope.jvmanalysis.ProjectLayoutDetector;
import dev.diagscope.jvmanalysis.AspectPointcutMatcher;
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.Disposable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.config.CommonConfigurationKeys;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.KtAnnotated;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtBinaryExpression;
import org.jetbrains.kotlin.psi.KtBlockExpression;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtCatchClause;
import org.jetbrains.kotlin.psi.KtClass;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtFinallySection;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtLoopExpression;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtObjectDeclaration;
import org.jetbrains.kotlin.psi.KtParameter;
import org.jetbrains.kotlin.psi.KtProperty;
import org.jetbrains.kotlin.psi.KtQualifiedExpression;
import org.jetbrains.kotlin.psi.KtReturnExpression;
import org.jetbrains.kotlin.psi.KtThrowExpression;
import org.jetbrains.kotlin.psi.KtTypeReference;
import org.jetbrains.kotlin.psi.ValueArgument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import java.util.regex.Pattern;

/** Syntax-first Kotlin/JVM source adapter backed by Kotlin PSI. */
public final class KotlinParserProjectAnalyzer implements ProjectAnalyzer {
    private static final Set<String> REST_MAPPING_ANNOTATIONS = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping"
    );
    private static final Set<String> SPRING_STEREOTYPES = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration",
            "ControllerAdvice", "RestControllerAdvice", "Aspect"
    );
    private static final Set<String> PROXIED_ANNOTATIONS = Set.of(
            "Transactional", "Async", "Cacheable", "CacheEvict", "CachePut", "Caching",
            "Retryable", "CircuitBreaker", "RateLimiter", "Bulkhead", "TimeLimiter",
            "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "Validated",
            "Observed", "Timed", "Counted", "NewSpan", "ContinueSpan"
    );
    private static final Set<String> LOGGER_METHODS = Set.of("trace", "debug", "info", "warn", "error", "log");
    private static final Set<String> OBSERVING_COMPLETION_METHODS = Set.of(
            "get", "join", "await", "whenComplete", "handle", "exceptionally", "thenAccept", "thenRun"
    );
    private static final Pattern STABLE_FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{2,}");
    private static final Pattern SILENT_CATCH_SUPPRESSION = Pattern.compile(
            "(?i)diagscope\\s*:\\s*ignore\\s+SILENT_CATCH\\s*--\\s*\\S.*"
    );

    @Override
    public AnalyzedProject analyze(Path projectDirectory, AnalysisOptions options) {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(options, "options");
        ProjectLayout layout = ProjectLayoutDetector.detect(projectDirectory);
        Path root = layout.root();
        List<Path> sourceFiles = discoverSourceFiles(layout.sourceRoots()).stream()
                .filter(file -> !options.policy().ignores(root.relativize(file.toAbsolutePath().normalize())))
                .toList();
        if (sourceFiles.isEmpty()) {
            return new AnalyzedProject(root.getFileName().toString(), root, layout, Map.of(), List.of(),
                    0, List.of(), List.of());
        }

        boolean kotlinSpringEnabled = detectsKotlinSpringPlugin(root);
        ParseBatch batch = parseFiles(root, sourceFiles, kotlinSpringEnabled, options.policy());
        MappedProject mapped = merge(batch.units());
        List<AspectAdvice> aspects = collectAspects(mapped);
        Map<MethodId, MethodModel> methods = resolveCalls(mapped, aspects);
        List<Entrypoint> entrypoints = detectEntrypoints(mapped.methods(),
                options.enabledEntrypointTypes(), options.policy());

        var failures = new ArrayList<ParseFailure>(batch.failures());
        failures.addAll(mapped.failures());
        failures.sort(Comparator.comparing(failure -> failure.file().toString()));
        return new AnalyzedProject(root.getFileName().toString(), root, layout, methods, entrypoints,
                sourceFiles.size(), failures, aspects);
    }

    private static List<Path> discoverSourceFiles(List<Path> sourceRoots) {
        var files = new TreeSet<Path>();
        for (Path sourceRoot : sourceRoots) {
            try (var stream = Files.find(sourceRoot, Integer.MAX_VALUE,
                    (path, attributes) -> attributes.isRegularFile() && path.toString().endsWith(".kt"))) {
                stream.forEach(files::add);
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to discover Kotlin sources under " + sourceRoot, exception);
            }
        }
        return List.copyOf(files);
    }

    /** Kotlin PSI shares an application environment, so the initial adapter parses deterministically in one session. */
    private static ParseBatch parseFiles(
            Path root,
            List<Path> files,
            boolean kotlinSpringEnabled,
            AnalysisPolicy policy
    ) {
        Disposable disposable = Disposer.newDisposable("diagscope-kotlin-parser");
        try {
            var configuration = new CompilerConfiguration();
            configuration.put(CommonConfigurationKeys.MODULE_NAME, "diagscope-analysis");
            KotlinCoreEnvironment environment = KotlinCoreEnvironment.createForProduction(
                    disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES);
            var factory = new org.jetbrains.kotlin.psi.KtPsiFactory(environment.getProject(), false);
            var units = new ArrayList<MappedUnit>(files.size());
            var failures = new ArrayList<ParseFailure>();
            for (Path file : files) {
                Path relative = root.relativize(file.toAbsolutePath().normalize());
                try {
                    String source = Files.readString(file);
                    KtFile ktFile = factory.createFile(file.getFileName().toString(), source);
                    units.add(mapFile(root, file, ktFile, new LineIndex(source), kotlinSpringEnabled, policy));
                    var errors = PsiTreeUtil.findChildrenOfType(ktFile, PsiErrorElement.class);
                    if (!errors.isEmpty()) {
                        String message = errors.stream().limit(3).map(PsiErrorElement::getErrorDescription)
                                .reduce((left, right) -> left + " | " + right).orElse("Kotlin PSI syntax error");
                        failures.add(new ParseFailure(relative, message));
                    }
                } catch (IOException | RuntimeException exception) {
                    failures.add(new ParseFailure(relative,
                            exception.getClass().getSimpleName() + ": " + safeMessage(exception)));
                }
            }
            return new ParseBatch(List.copyOf(units), List.copyOf(failures));
        } finally {
            Disposer.dispose(disposable);
        }
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? "No diagnostic message" : throwable.getMessage();
    }

    private static MappedUnit mapFile(
            Path root,
            Path file,
            KtFile ktFile,
            LineIndex lines,
            boolean kotlinSpringEnabled,
            AnalysisPolicy policy
    ) {
        String packageName = ktFile.getPackageFqName().asString();
        var types = new ArrayList<TypeInfo>();
        for (KtClassOrObject type : PsiTreeUtil.findChildrenOfType(ktFile, KtClassOrObject.class)) {
            if (type.isLocal()) continue;
            types.add(typeInfo(type, kotlinSpringEnabled));
        }

        var methods = new ArrayList<RawMethod>();
        for (KtNamedFunction function : PsiTreeUtil.findChildrenOfType(ktFile, KtNamedFunction.class)) {
            if (function.isLocal() || function.isAnonymous()) continue;
            KtClassOrObject owner = containingType(function);
            String declaringType = owner == null
                    ? topLevelOwner(packageName, file)
                    : qualifiedTypeName(packageName, owner);
            List<AnnotationDescriptor> typeAnnotations = owner == null
                    ? List.of() : annotations(owner);
            methods.add(mapFunction(root, file, lines, declaringType, owner, function,
                    typeAnnotations, kotlinSpringEnabled, policy));
        }
        return new MappedUnit(root.relativize(file.toAbsolutePath().normalize()), methods, types,
                ktFile.getText().contains("ProducerListener") || ktFile.getText().contains("setProducerListener("));
    }

    private static RawMethod mapFunction(
            Path root,
            Path file,
            LineIndex lines,
            String declaringType,
            KtClassOrObject owner,
            KtNamedFunction function,
            List<AnnotationDescriptor> typeAnnotations,
            boolean kotlinSpringEnabled,
            AnalysisPolicy policy
    ) {
        MethodId id = new MethodId(declaringType, Objects.requireNonNullElse(function.getName(), "<anonymous>"),
                function.getValueParameters().stream().map(KotlinParserProjectAnalyzer::parameterType).toList());
        SourceLocation location = location(root, file, lines, function);
        List<AnnotationDescriptor> methodAnnotations = annotations(function);
        var annotationNames = new TreeSet<String>();
        typeAnnotations.forEach(annotation -> annotationNames.add(annotation.name()));
        methodAnnotations.forEach(annotation -> annotationNames.add(annotation.name()));

        Map<String, String> variableTypes = declaredVariables(owner);
        var fieldNames = new LinkedHashSet<>(variableTypes.keySet());
        Set<String> constantFieldNames = constantVariables(owner);
        var parameterNames = new LinkedHashSet<String>();
        for (KtParameter parameter : function.getValueParameters()) {
            if (parameter.getName() != null) {
                parameterNames.add(parameter.getName());
                variableTypes.put(parameter.getName(), parameterType(parameter));
            }
        }
        var localNames = new LinkedHashSet<String>();
        for (KtProperty property : PsiTreeUtil.findChildrenOfType(function, KtProperty.class)) {
            if (!belongsToFunction(property, function) || property.getName() == null) continue;
            localNames.add(property.getName());
            variableTypes.put(property.getName(), propertyType(property));
        }
        for (KtCatchClause clause : PsiTreeUtil.findChildrenOfType(function, KtCatchClause.class)) {
            if (!belongsToFunction(clause, function) || clause.getCatchParameter() == null) continue;
            KtParameter parameter = clause.getCatchParameter();
            if (parameter.getName() != null) variableTypes.put(parameter.getName(), parameterType(parameter));
        }

        var catches = PsiTreeUtil.findChildrenOfType(function, KtCatchClause.class).stream()
                .filter(clause -> belongsToFunction(clause, function))
                .map(clause -> catchEvidence(root, file, lines, clause, variableTypes, policy))
                .sorted(Comparator.comparingInt(evidence -> evidence.location().startLine()))
                .toList();

        var invocations = new ArrayList<InvocationEvidence>();
        var metricTags = new ArrayList<MetricTagEvidence>();
        var metricNames = new ArrayList<MetricNameEvidence>();
        var calls = new ArrayList<RawCall>();
        var metricContext = new KotlinMetricEvidenceExtractor.Names(variableTypes, parameterNames,
                localNames, fieldNames, constantFieldNames);
        var callExpressions = PsiTreeUtil.findChildrenOfType(function, KtCallExpression.class).stream()
                .filter(call -> belongsToFunction(call, function))
                .sorted(Comparator.comparingInt(PsiElement::getTextOffset))
                .toList();
        for (KtCallExpression call : callExpressions) {
            String methodName = methodName(call);
            if (methodName.isBlank()) continue;
            SourceLocation callLocation = location(root, file, lines, call);
            String scope = scope(call);
            String receiverType = Optional.ofNullable(receiverType(scope, variableTypes))
                    .orElseGet(() -> inferReceiverType(scope));
            invocations.add(invocationEvidence(callLocation, call, methodName, scope, receiverType,
                    isLoggerCall(call, variableTypes, policy)));
            metricTags.addAll(KotlinMetricEvidenceExtractor.tags(callLocation, call, metricContext));
            KotlinMetricEvidenceExtractor.meter(callLocation, call, metricContext).ifPresent(metricNames::add);
            calls.add(new RawCall(callLocation, scope, receiverType, methodName, argumentCount(call)));
        }

        int minimumArity = (int) function.getValueParameters().stream()
                .filter(parameter -> !parameter.hasDefaultValue() && !parameter.isVarArg()).count();
        boolean vararg = function.getValueParameters().stream().anyMatch(KtParameter::isVarArg);
        int maximumArity = vararg ? Integer.MAX_VALUE : function.getValueParameters().size();
        boolean springOpened = kotlinSpringEnabled && typeAnnotations.stream()
                .map(AnnotationDescriptor::name).anyMatch(SPRING_STEREOTYPES::contains);
        String returnType = function.getTypeReference() == null ? "" : function.getTypeReference().getTypeText();
        return new RawMethod(id, location, Set.copyOf(annotationNames), typeAnnotations, methodAnnotations,
                catches, List.copyOf(invocations), dedupeTags(metricTags), dedupeMeters(metricNames),
                List.copyOf(calls), Map.copyOf(variableTypes),
                visibility(function), false, effectiveFinal(function, springOpened), returnType,
                minimumArity, maximumArity, owner == null);
    }

    private static CatchEvidence catchEvidence(
            Path root,
            Path file,
            LineIndex lines,
            KtCatchClause clause,
            Map<String, String> variableTypes,
            AnalysisPolicy policy
    ) {
        KtExpression body = clause.getCatchBody();
        KtParameter parameter = clause.getCatchParameter();
        String exceptionVariable = parameter == null || parameter.getName() == null ? "" : parameter.getName();
        String exceptionType = parameter == null ? "Throwable" : parameterType(parameter);
        List<KtCallExpression> calls = body == null ? List.of()
                : List.copyOf(PsiTreeUtil.findChildrenOfType(body, KtCallExpression.class));
        boolean hasLog = calls.stream().anyMatch(call -> isLoggerCall(call, variableTypes, policy));
        List<KtThrowExpression> throwsFound = body == null ? List.of()
                : List.copyOf(PsiTreeUtil.findChildrenOfType(body, KtThrowExpression.class));
        List<KtReturnExpression> returns = body == null ? List.of()
                : List.copyOf(PsiTreeUtil.findChildrenOfType(body, KtReturnExpression.class));
        KtExpression fallback = returns.stream().map(KtReturnExpression::getReturnedExpression)
                .filter(Objects::nonNull).findFirst().orElseGet(() -> fallbackExpression(body));
        String returnedExpression = fallback == null ? "" : fallback.getText();
        boolean hasReturn = !returns.isEmpty() || fallback != null;
        boolean preservesCause = !exceptionVariable.isBlank()
                && (returnedExpression.matches("(?s).*\\b" + Pattern.quote(exceptionVariable) + "\\b.*")
                || throwsFound.stream().anyMatch(item -> item.getText().contains(exceptionVariable)));
        boolean stableFailureCode = STABLE_FAILURE_CODE.matcher(stripQuotes(returnedExpression)).matches()
                || Pattern.compile(".*\\b[A-Z][A-Z0-9_.-]{2,}\\b.*").matcher(returnedExpression).matches();
        boolean suppression = body != null && SILENT_CATCH_SUPPRESSION.matcher(body.getText()).find();
        boolean empty = body instanceof KtBlockExpression block && block.getStatements().isEmpty();
        return new CatchEvidence(location(root, file, lines, clause), exceptionType, empty, hasLog,
                !throwsFound.isEmpty(), hasReturn, returnedExpression, suppression, preservesCause, stableFailureCode);
    }

    /** Returns the value of a Kotlin catch expression only when it visibly converts failure to a benign value. */
    private static KtExpression fallbackExpression(KtExpression body) {
        if (!(body instanceof KtBlockExpression block) || block.getStatements().isEmpty()) return null;
        KtExpression last = block.getStatements().getLast();
        String text = last.getText().replace(" ", "");
        if (text.matches("null|false|true|0|-1|empty(List|Set|Map|Sequence)\\(\\)|Result\\.success\\(.*\\)")) {
            return last;
        }
        return null;
    }

    private static InvocationEvidence invocationEvidence(
            SourceLocation location,
            KtCallExpression call,
            String methodName,
            String scope,
            String receiverType,
            boolean loggerReceiver
    ) {
        return new InvocationEvidence(location, scope, receiverType, methodName,
                call.getValueArguments().stream()
                        .map(ValueArgument::getArgumentExpression)
                        .map(expression -> expression == null ? "" : expression.getText())
                        .toList(),
                resultUsage(call), false, resourceManaged(call), assignedVariable(call),
                hasAncestor(call, KtFinallySection.class), hasAncestor(call, KtLoopExpression.class),
                loggerReceiver);
    }

    private static InvocationResultUsage resultUsage(KtCallExpression call) {
        PsiElement current = call;
        while (current.getParent() != null) {
            PsiElement parent = current.getParent();
            if (parent instanceof KtReturnExpression) return InvocationResultUsage.RETURNED;
            if (parent instanceof KtProperty property && property.getInitializer() == current) {
                return InvocationResultUsage.ASSIGNED;
            }
            if (parent instanceof KtBinaryExpression binary && binary.getRight() == current
                    && "=".equals(binary.getOperationReference().getText())) {
                return InvocationResultUsage.ASSIGNED;
            }
            if (parent instanceof KtCallExpression outer) {
                boolean argument = outer.getValueArguments().stream()
                        .map(ValueArgument::getArgumentExpression).anyMatch(current::equals);
                if (argument) return InvocationResultUsage.USED_AS_ARGUMENT;
            }
            if (parent instanceof KtQualifiedExpression qualified) {
                KtExpression selector = qualified.getSelectorExpression();
                if (selector == current) {
                    current = (PsiElement) qualified;
                    continue;
                }
                String outerName = selector instanceof KtCallExpression outer ? methodName(outer) : "";
                return OBSERVING_COMPLETION_METHODS.contains(outerName)
                        ? InvocationResultUsage.OBSERVED : InvocationResultUsage.CHAINED;
            }
            if (parent instanceof KtNamedFunction function && function.getBodyExpression() == current
                    && !function.hasBlockBody()) {
                return InvocationResultUsage.RETURNED;
            }
            if (parent instanceof KtBlockExpression block && block.getStatements().contains(current)) {
                return InvocationResultUsage.IGNORED;
            }
            if (!(parent instanceof KtExpression)) break;
            current = parent;
        }
        return InvocationResultUsage.UNKNOWN;
    }

    private static boolean resourceManaged(KtCallExpression call) {
        PsiElement current = call;
        while (current.getParent() instanceof KtQualifiedExpression qualified) {
            KtExpression selector = qualified.getSelectorExpression();
            if (selector instanceof KtCallExpression outer && "use".equals(methodName(outer))) return true;
            current = (PsiElement) qualified;
        }
        return false;
    }

    private static String assignedVariable(KtCallExpression call) {
        PsiElement current = call;
        while (current.getParent() != null) {
            PsiElement parent = current.getParent();
            if (parent instanceof KtProperty property && property.getInitializer() == current) {
                return Objects.requireNonNullElse(property.getName(), "");
            }
            if (parent instanceof KtBinaryExpression binary && binary.getRight() == current
                    && "=".equals(binary.getOperationReference().getText())) {
                return binary.getLeft() == null ? "" : binary.getLeft().getText();
            }
            if (parent instanceof KtQualifiedExpression || parent instanceof KtExpression) {
                current = parent;
                continue;
            }
            break;
        }
        return "";
    }

    private static boolean hasAncestor(PsiElement element, Class<? extends PsiElement> type) {
        PsiElement current = element.getParent();
        while (current != null) {
            if (type.isInstance(current)) return true;
            if (current instanceof KtNamedFunction) return false;
            current = current.getParent();
        }
        return false;
    }

    private static boolean isLoggerCall(
            KtCallExpression call,
            Map<String, String> variableTypes,
            AnalysisPolicy policy
    ) {
        String name = methodName(call);
        if (!LOGGER_METHODS.contains(name)) return false;
        String rawScope = scope(call);
        String type = Objects.requireNonNullElse(receiverType(rawScope, variableTypes), "");
        String hint = (rawScope + ' ' + type).toLowerCase(Locale.ROOT);
        return hint.contains("logger") || hint.matches(".*\\blog\\b.*") || hint.contains("kotlinlogging")
                || policy.isCustomLogger(rawScope, type);
    }

    private static MappedProject merge(List<MappedUnit> units) {
        var methods = new LinkedHashMap<MethodId, RawMethod>();
        var types = new LinkedHashMap<String, TypeInfo>();
        var failures = new ArrayList<ParseFailure>();
        for (MappedUnit unit : units) {
            for (TypeInfo type : unit.types()) {
                if (types.putIfAbsent(type.qualifiedName(), type) != null) {
                    failures.add(new ParseFailure(unit.file(), "Duplicate Kotlin type identity ignored: "
                            + type.qualifiedName()));
                }
            }
            for (RawMethod method : unit.methods()) {
                if (methods.putIfAbsent(method.id(), method) != null) {
                    failures.add(new ParseFailure(method.location().file(),
                            "Duplicate Kotlin method identity ignored: " + method.id().displayName()));
                }
            }
        }
        return new MappedProject(Collections.unmodifiableMap(methods), Collections.unmodifiableMap(types),
                List.copyOf(failures), units.stream().anyMatch(MappedUnit::producerListenerVisible));
    }

    private static Map<MethodId, MethodModel> resolveCalls(MappedProject project, List<AspectAdvice> aspects) {
        var byTypeAndName = new HashMap<String, List<RawMethod>>();
        var bySimpleTypeAndName = new HashMap<String, List<RawMethod>>();
        for (RawMethod method : project.methods().values()) {
            byTypeAndName.computeIfAbsent(method.id().declaringType() + '#' + method.id().name(),
                    ignored -> new ArrayList<>()).add(method);
            bySimpleTypeAndName.computeIfAbsent(simpleName(method.id().declaringType()) + '#' + method.id().name(),
                    ignored -> new ArrayList<>()).add(method);
        }
        var typesBySimpleName = new HashMap<String, List<TypeInfo>>();
        project.types().values().forEach(type -> typesBySimpleName
                .computeIfAbsent(type.simpleName(), ignored -> new ArrayList<>()).add(type));
        typesBySimpleName.values().forEach(list -> list.sort(Comparator.comparing(TypeInfo::qualifiedName)));
        var implementationsBySuperType = new HashMap<String, List<TypeInfo>>();
        for (TypeInfo type : project.types().values()) {
            for (String superType : type.superTypes()) {
                implementationsBySuperType.computeIfAbsent(simpleName(superType), ignored -> new ArrayList<>())
                        .add(type);
            }
        }
        implementationsBySuperType.values().forEach(list ->
                list.sort(Comparator.comparing(TypeInfo::qualifiedName)));

        var result = new LinkedHashMap<MethodId, MethodModel>();
        for (RawMethod raw : project.methods().values()) {
            var calls = new ArrayList<MethodCall>();
            for (RawCall call : raw.calls()) {
                Resolution resolution = resolveCall(raw, call, project.types(), typesBySimpleName,
                        implementationsBySuperType, byTypeAndName, bySimpleTypeAndName);
                calls.add(new MethodCall(call.location(), call.scope(), call.methodName(), call.argumentCount(),
                        resolution.target(), resolution.reason()));
            }
            List<InvocationEvidence> invocations = project.producerListenerVisible()
                    ? raw.invocations().stream().map(item -> item.withProducerListenerVisible(true)).toList()
                    : raw.invocations();
            result.put(raw.id(), new MethodModel(raw.id(), raw.location(), raw.annotations(), raw.catches(),
                    invocations, raw.metricTags(), raw.metricNames(), calls,
                    proxyProfile(raw, project.types(), aspects),
                    annotationAttributes(raw)));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Resolution resolveCall(
            RawMethod caller,
            RawCall call,
            Map<String, TypeInfo> types,
            Map<String, List<TypeInfo>> typesBySimpleName,
            Map<String, List<TypeInfo>> implementationsBySuperType,
            Map<String, List<RawMethod>> byTypeAndName,
            Map<String, List<RawMethod>> bySimpleTypeAndName
    ) {
        if (call.scope().isBlank() || "this".equals(call.scope())) {
            return choose(byTypeAndName.get(caller.id().declaringType() + '#' + call.methodName()),
                    call.argumentCount(), ResolutionReason.SAME_CLASS);
        }
        if (!call.receiverType().isBlank()) {
            String receiver = simpleName(call.receiverType());
            List<TypeInfo> receiverTypes = typesBySimpleName.getOrDefault(receiver, List.of());
            if (receiverTypes.size() > 1 && !types.containsKey(baseTypeName(call.receiverType()))) {
                return new Resolution(Optional.empty(), ResolutionReason.AMBIGUOUS);
            }
            TypeInfo receiverInfo = Optional.ofNullable(types.get(baseTypeName(call.receiverType())))
                    .orElseGet(() -> receiverTypes.size() == 1 ? receiverTypes.getFirst() : null);
            if (receiverInfo == null) {
                return new Resolution(Optional.empty(), ResolutionReason.EXTERNAL);
            }
            if (!receiverInfo.interfaceType()) {
                return choose(byTypeAndName.get(receiverInfo.qualifiedName() + '#' + call.methodName()),
                        call.argumentCount(), ResolutionReason.DECLARED_RECEIVER);
            }

            var implementations = new ArrayList<RawMethod>();
            for (TypeInfo type : implementationsBySuperType.getOrDefault(receiver, List.of())) {
                List<RawMethod> methods = byTypeAndName.get(type.qualifiedName() + '#' + call.methodName());
                if (methods != null) {
                    methods.stream().filter(candidate -> candidate.acceptsArity(call.argumentCount()))
                            .forEach(implementations::add);
                }
            }
            if (implementations.size() == 1) {
                return new Resolution(Optional.of(implementations.getFirst().id()),
                        ResolutionReason.SINGLE_IMPLEMENTATION);
            }
            if (implementations.size() > 1) {
                return new Resolution(Optional.empty(), ResolutionReason.AMBIGUOUS);
            }
            return new Resolution(Optional.empty(), ResolutionReason.UNRESOLVED);
        }
        if (looksLikeType(call.scope())) {
            String possibleType = simpleName(call.scope());
            if (typesBySimpleName.containsKey(possibleType)) {
                return choose(bySimpleTypeAndName.get(possibleType + '#' + call.methodName()),
                        call.argumentCount(), ResolutionReason.DECLARED_RECEIVER);
            }
            return new Resolution(Optional.empty(), ResolutionReason.EXTERNAL);
        }
        return new Resolution(Optional.empty(), ResolutionReason.UNRESOLVED);
    }

    private static Resolution choose(List<RawMethod> candidates, int arity, ResolutionReason success) {
        if (candidates == null) return new Resolution(Optional.empty(), ResolutionReason.UNRESOLVED);
        List<RawMethod> matches = candidates.stream().filter(candidate -> candidate.acceptsArity(arity)).limit(2).toList();
        if (matches.size() == 1) return new Resolution(Optional.of(matches.getFirst().id()), success);
        if (matches.size() > 1) return new Resolution(Optional.empty(), ResolutionReason.AMBIGUOUS);
        return new Resolution(Optional.empty(), ResolutionReason.UNRESOLVED);
    }

    private static List<Entrypoint> detectEntrypoints(
            Map<MethodId, RawMethod> methods,
            Set<EntrypointType> enabledTypes,
            AnalysisPolicy policy
    ) {
        var result = new ArrayList<Entrypoint>();
        for (RawMethod method : methods.values()) {
            if (enabledTypes.contains(EntrypointType.REST)) detectRest(method).ifPresent(result::add);
            if (enabledTypes.contains(EntrypointType.KAFKA_LISTENER)) detectKafka(method).ifPresent(result::add);
            if (enabledTypes.contains(EntrypointType.SCHEDULED)) {
                annotation(method.methodAnnotations(), "Scheduled").ifPresent(item -> result.add(new Entrypoint(
                        EntrypointType.SCHEDULED, method.id(), scheduleDisplay(item), method.location())));
            }
            addCustomEntrypoints(result, method, enabledTypes, policy);
        }
        result.sort(Comparator.comparing((Entrypoint item) -> item.type().name())
                .thenComparing(Entrypoint::displayName).thenComparing(item -> item.method().displayName()));
        return List.copyOf(result);
    }

    private static void addCustomEntrypoints(
            List<Entrypoint> result,
            RawMethod method,
            Set<EntrypointType> enabledTypes,
            AnalysisPolicy policy
    ) {
        Set<String> methodAnnotations = method.methodAnnotations().stream()
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

    private static Optional<Entrypoint> detectRest(RawMethod method) {
        boolean controller = annotation(method.typeAnnotations(), "RestController").isPresent()
                || annotation(method.typeAnnotations(), "Controller").isPresent();
        if (!controller) return Optional.empty();
        Optional<AnnotationDescriptor> mapping = method.methodAnnotations().stream()
                .filter(item -> REST_MAPPING_ANNOTATIONS.contains(item.name())).findFirst();
        if (mapping.isEmpty()) return Optional.empty();
        String prefix = annotation(method.typeAnnotations(), "RequestMapping")
                .flatMap(item -> firstAttribute(item, "path", "value")).orElse("");
        String suffix = firstAttribute(mapping.orElseThrow(), "path", "value").orElse("");
        return Optional.of(new Entrypoint(EntrypointType.REST, method.id(),
                restVerb(mapping.orElseThrow()) + ' ' + combinePaths(prefix, suffix), method.location()));
    }

    private static Optional<Entrypoint> detectKafka(RawMethod method) {
        Optional<AnnotationDescriptor> listener = annotation(method.methodAnnotations(), "KafkaListener");
        if (listener.isPresent()) {
            return Optional.of(new Entrypoint(EntrypointType.KAFKA_LISTENER, method.id(),
                    kafkaDisplay(listener.orElseThrow()), method.location()));
        }
        boolean classListener = annotation(method.typeAnnotations(), "KafkaListener").isPresent();
        boolean handler = annotation(method.methodAnnotations(), "KafkaHandler").isPresent()
                || annotation(method.methodAnnotations(), "DltHandler").isPresent();
        if (!classListener || !handler) return Optional.empty();
        return Optional.of(new Entrypoint(EntrypointType.KAFKA_LISTENER, method.id(),
                kafkaDisplay(annotation(method.typeAnnotations(), "KafkaListener").orElseThrow()), method.location()));
    }

    private static String restVerb(AnnotationDescriptor annotation) {
        return switch (annotation.name()) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "PatchMapping" -> "PATCH";
            case "DeleteMapping" -> "DELETE";
            case "RequestMapping" -> annotation.attributes().getOrDefault("method", "HTTP")
                    .replace("RequestMethod.", "").replace("[", "").replace("]", "")
                    .split(",", 2)[0].trim();
            default -> "HTTP";
        };
    }

    private static String kafkaDisplay(AnnotationDescriptor annotation) {
        return firstAttribute(annotation, "topics", "value").map(value -> "Kafka topic=" + value)
                .or(() -> firstAttribute(annotation, "topicPattern").map(value -> "Kafka topicPattern=" + value))
                .or(() -> firstAttribute(annotation, "topicPartitions").map(value -> "Kafka topicPartitions=" + value))
                .orElse("Kafka topic=unknown");
    }

    private static String scheduleDisplay(AnnotationDescriptor annotation) {
        for (String name : List.of("cron", "fixedRateString", "fixedRate", "fixedDelayString", "fixedDelay")) {
            String value = annotation.attributes().get(name);
            if (value != null && !value.isBlank()) return "Scheduled " + name + '=' + value;
        }
        return "Scheduled";
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

    private static List<AspectAdvice> collectAspects(MappedProject project) {
        var result = new ArrayList<AspectAdvice>();
        for (RawMethod method : project.methods().values()) {
            Set<String> typeAnnotations = new TreeSet<>();
            method.typeAnnotations().forEach(item -> typeAnnotations.add(item.name()));
            if (!typeAnnotations.contains("Aspect")) continue;
            boolean managed = typeAnnotations.stream().anyMatch(name -> SPRING_STEREOTYPES.contains(name)
                    && !"Aspect".equals(name));
            for (AnnotationDescriptor annotation : method.methodAnnotations()) {
                AdviceKind.fromAnnotation(annotation.name()).ifPresent(kind -> result.add(new AspectAdvice(
                        method.id().declaringType(), method.id().name(), kind,
                        firstAttribute(annotation, "value", "pointcut").orElse(""), method.location(), managed)));
            }
        }
        result.sort(Comparator.comparing(AspectAdvice::id).thenComparing(item -> item.kind().name()));
        return List.copyOf(result);
    }

    private static ProxyProfile proxyProfile(
            RawMethod method,
            Map<String, TypeInfo> types,
            List<AspectAdvice> aspects
    ) {
        TypeInfo type = types.get(method.id().declaringType());
        var proxied = new TreeSet<String>();
        method.annotations().stream().filter(PROXIED_ANNOTATIONS::contains).forEach(proxied::add);
        var matchingAdvice = new ArrayList<String>();
        for (AspectAdvice aspect : aspects) {
            if (aspect.aspectType().equals(method.id().declaringType())) continue;
            var target = new AspectPointcutMatcher.Target(
                    method.id().declaringType(), method.id().name(), method.annotations());
            if (AspectPointcutMatcher.matches(aspect.pointcut(), target)) {
                matchingAdvice.add(aspect.displayName());
            }
        }
        return new ProxyProfile(method.visibility(), method.staticMethod(), method.finalMethod(),
                type != null && type.finalType(), type != null && type.springManaged(), false,
                proxied, matchingAdvice);
    }

    private static Map<String, Map<String, String>> annotationAttributes(RawMethod method) {
        var result = new LinkedHashMap<String, Map<String, String>>();
        method.typeAnnotations().stream().filter(item -> !item.attributes().isEmpty())
                .forEach(item -> result.put(item.name(), item.attributes()));
        method.methodAnnotations().stream().filter(item -> !item.attributes().isEmpty())
                .forEach(item -> result.put(item.name(), item.attributes()));
        return Collections.unmodifiableMap(result);
    }

    private static TypeInfo typeInfo(KtClassOrObject type, boolean kotlinSpringEnabled) {
        String qualified = qualifiedTypeName(type.getContainingKtFile().getPackageFqName().asString(), type);
        Set<String> annotationNames = annotations(type).stream().map(AnnotationDescriptor::name)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        boolean springManaged = annotationNames.stream().anyMatch(SPRING_STEREOTYPES::contains);
        boolean springOpened = kotlinSpringEnabled && springManaged;
        boolean interfaceType = type instanceof KtClass klass && klass.isInterface();
        boolean finalType = !interfaceType && !springOpened && !type.hasModifier(KtTokens.OPEN_KEYWORD)
                && !type.hasModifier(KtTokens.ABSTRACT_KEYWORD) && !type.hasModifier(KtTokens.SEALED_KEYWORD);
        Set<String> superTypes = type.getSuperTypeListEntries().stream()
                .map(PsiElement::getText).map(KotlinParserProjectAnalyzer::baseTypeName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new TypeInfo(qualified, simpleName(qualified), interfaceType, Set.copyOf(superTypes),
                Set.copyOf(annotationNames), finalType, springManaged);
    }

    private static Map<String, String> declaredVariables(KtClassOrObject owner) {
        if (owner == null) return new LinkedHashMap<>();
        var result = new LinkedHashMap<String, String>();
        for (KtParameter parameter : owner.getPrimaryConstructorParameters()) {
            if (parameter.hasValOrVar() && parameter.getName() != null) {
                result.put(parameter.getName(), parameterType(parameter));
            }
        }
        for (KtProperty property : PsiTreeUtil.findChildrenOfType(owner, KtProperty.class)) {
            if (property.isLocal() || containingType(property) != owner || property.getName() == null) continue;
            result.put(property.getName(), propertyType(property));
        }
        return result;
    }

    private static Set<String> constantVariables(KtClassOrObject owner) {
        if (owner == null) return Set.of();
        var result = new LinkedHashSet<String>();
        for (KtProperty property : PsiTreeUtil.findChildrenOfType(owner, KtProperty.class)) {
            if (property.isLocal() || containingType(property) != owner || property.getName() == null) continue;
            String name = property.getName();
            if (property.hasModifier(KtTokens.CONST_KEYWORD) || name.equals(name.toUpperCase(Locale.ROOT))) {
                result.add(name);
            }
        }
        return Set.copyOf(result);
    }

    private static List<MetricTagEvidence> dedupeTags(List<MetricTagEvidence> tags) {
        var unique = new LinkedHashMap<String, MetricTagEvidence>();
        for (MetricTagEvidence tag : tags) {
            String key = tag.location() + "|" + tag.tagName() + "|" + tag.valueExpression();
            unique.putIfAbsent(key, tag);
        }
        return List.copyOf(unique.values());
    }

    private static List<MetricNameEvidence> dedupeMeters(List<MetricNameEvidence> meters) {
        var unique = new LinkedHashMap<String, MetricNameEvidence>();
        for (MetricNameEvidence meter : meters) {
            String key = meter.location() + "|" + meter.meterType() + "|" + meter.nameExpression();
            unique.putIfAbsent(key, meter);
        }
        return List.copyOf(unique.values());
    }

    private static String propertyType(KtProperty property) {
        if (property.getTypeReference() != null) return property.getTypeReference().getTypeText();
        KtExpression initializer = property.getInitializer();
        if (initializer instanceof KtCallExpression call) return methodName(call);
        return "";
    }

    private static String parameterType(KtParameter parameter) {
        KtTypeReference reference = parameter.getTypeReference();
        return reference == null ? "Any" : reference.getTypeText();
    }

    private static List<AnnotationDescriptor> annotations(KtAnnotated annotated) {
        var result = new ArrayList<AnnotationDescriptor>();
        for (KtAnnotationEntry annotation : annotated.getAnnotationEntries()) {
            if (annotation.getShortName() == null) continue;
            var attributes = new TreeMap<String, String>();
            int positional = 0;
            for (ValueArgument argument : annotation.getValueArguments()) {
                String name = argument.getArgumentName() == null
                        ? (positional++ == 0 ? "value" : "value" + positional)
                        : argument.getArgumentName().getAsName().asString();
                KtExpression expression = argument.getArgumentExpression();
                attributes.put(name, annotationValue(expression == null ? "" : expression.getText()));
            }
            result.add(new AnnotationDescriptor(annotation.getShortName().asString(), attributes));
        }
        return List.copyOf(result);
    }

    private static Optional<AnnotationDescriptor> annotation(List<AnnotationDescriptor> values, String name) {
        return values.stream().filter(item -> name.equals(item.name())).findFirst();
    }

    private static Optional<String> firstAttribute(AnnotationDescriptor annotation, String... names) {
        for (String name : names) {
            String value = annotation.attributes().get(name);
            if (value != null && !value.isBlank()) return Optional.of(value.split(",", 2)[0].trim());
        }
        return Optional.empty();
    }

    private static String annotationValue(String value) {
        String normalized = value.trim();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return stripQuotes(normalized);
    }

    private static String stripQuotes(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static String methodName(KtCallExpression call) {
        return call.getCalleeExpression() == null ? "" : call.getCalleeExpression().getText();
    }

    private static int argumentCount(KtCallExpression call) {
        return call.getValueArguments().size() + call.getLambdaArguments().size();
    }

    private static String scope(KtCallExpression call) {
        if (call.getParent() instanceof KtQualifiedExpression qualified
                && qualified.getSelectorExpression() == call) {
            return qualified.getReceiverExpression().getText();
        }
        return "";
    }

    private static String receiverType(String scope, Map<String, String> variables) {
        String direct = variables.get(scope);
        if (direct != null) return direct;
        if (scope.startsWith("this.")) return variables.get(scope.substring("this.".length()));
        return null;
    }

    private static String inferReceiverType(String scope) {
        if (scope.isBlank()) return "";
        if (scope.startsWith("System.")) return scope;
        String root = scope.contains(".") ? scope.substring(0, scope.indexOf('.')) : scope;
        return !root.isBlank() && Character.isUpperCase(root.charAt(0)) ? root : "";
    }

    private static boolean belongsToFunction(PsiElement element, KtNamedFunction function) {
        PsiElement current = element.getParent();
        while (current != null) {
            if (current instanceof KtNamedFunction ancestor) return ancestor == function;
            current = current.getParent();
        }
        return false;
    }

    private static KtClassOrObject containingType(PsiElement element) {
        PsiElement current = element.getParent();
        while (current != null) {
            if (current instanceof KtClassOrObject type) return type;
            if (current instanceof KtNamedFunction) return null;
            current = current.getParent();
        }
        return null;
    }

    private static String qualifiedTypeName(String packageName, KtClassOrObject type) {
        if (type.getFqName() != null) return type.getFqName().asString();
        var names = new ArrayList<String>();
        PsiElement current = type;
        while (current instanceof KtClassOrObject declaration) {
            String name = declaration instanceof KtObjectDeclaration object && object.isCompanion()
                    ? "Companion" : declaration.getName();
            names.add(Objects.requireNonNullElse(name, "<anonymous>"));
            current = current.getParent();
            while (current != null && !(current instanceof KtClassOrObject) && !(current instanceof KtFile)) {
                current = current.getParent();
            }
        }
        Collections.reverse(names);
        String local = String.join(".", names);
        return packageName.isBlank() ? local : packageName + '.' + local;
    }

    private static String topLevelOwner(String packageName, Path file) {
        String fileName = file.getFileName().toString();
        String base = fileName.substring(0, fileName.length() - ".kt".length());
        String owner = base.isBlank() ? "FileKt" : Character.toUpperCase(base.charAt(0)) + base.substring(1) + "Kt";
        return packageName.isBlank() ? owner : packageName + '.' + owner;
    }

    private static MethodVisibility visibility(KtNamedFunction function) {
        if (function.hasModifier(KtTokens.PRIVATE_KEYWORD)) return MethodVisibility.PRIVATE;
        if (function.hasModifier(KtTokens.PROTECTED_KEYWORD)) return MethodVisibility.PROTECTED;
        return MethodVisibility.PUBLIC;
    }

    private static boolean effectiveFinal(KtNamedFunction function, boolean springOpened) {
        return !springOpened && !function.hasModifier(KtTokens.OPEN_KEYWORD)
                && !function.hasModifier(KtTokens.ABSTRACT_KEYWORD)
                && !function.hasModifier(KtTokens.OVERRIDE_KEYWORD);
    }

    private static boolean detectsKotlinSpringPlugin(Path root) {
        for (String descriptor : List.of("pom.xml", "build.gradle", "build.gradle.kts")) {
            try (var paths = Files.find(root, 5, (path, attributes) ->
                    attributes.isRegularFile() && descriptor.equals(path.getFileName().toString())
                            && !path.toString().contains("/target/") && !path.toString().contains("/build/"))) {
                boolean found = paths.anyMatch(path -> {
                    try {
                        String text = Files.readString(path);
                        return text.contains("plugin.spring") || text.contains("kotlin-spring")
                                || text.matches("(?s).*<compilerPlugin>\\s*spring\\s*</compilerPlugin>.*");
                    } catch (IOException ignored) {
                        return false;
                    }
                });
                if (found) return true;
            } catch (IOException ignored) {
                // Build-plugin detection improves proxy precision but is not required to parse sources.
            }
        }
        return false;
    }

    private static SourceLocation location(Path root, Path file, LineIndex lines, PsiElement element) {
        return new SourceLocation(root.relativize(file.toAbsolutePath().normalize()),
                lines.lineOf(element.getTextRange().getStartOffset()),
                lines.lineOf(Math.max(element.getTextRange().getStartOffset(), element.getTextRange().getEndOffset() - 1)));
    }

    private static boolean looksLikeType(String scope) {
        String simple = simpleName(scope);
        return !simple.isBlank() && Character.isUpperCase(simple.charAt(0));
    }

    private static String simpleName(String type) {
        String normalized = baseTypeName(type);
        int dot = normalized.lastIndexOf('.');
        return dot >= 0 ? normalized.substring(dot + 1) : normalized;
    }

    private static String baseTypeName(String type) {
        String normalized = type == null ? "" : type.trim();
        int generic = normalized.indexOf('<');
        if (generic >= 0) normalized = normalized.substring(0, generic);
        while (normalized.endsWith("?")) normalized = normalized.substring(0, normalized.length() - 1);
        int constructor = normalized.indexOf('(');
        if (constructor >= 0) normalized = normalized.substring(0, constructor);
        return normalized;
    }

    private record LineIndex(int[] newlineOffsets) {
        private LineIndex(String source) {
            this(java.util.stream.IntStream.range(0, source.length())
                    .filter(index -> source.charAt(index) == '\n')
                    .toArray());
        }

        private int lineOf(int offset) {
            int low = 0;
            int high = newlineOffsets.length;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (newlineOffsets[middle] < offset) low = middle + 1;
                else high = middle;
            }
            return low + 1;
        }
    }

    private record AnnotationDescriptor(String name, Map<String, String> attributes) {
        private AnnotationDescriptor {
            attributes = Collections.unmodifiableMap(new TreeMap<>(attributes));
        }
    }

    private record TypeInfo(String qualifiedName, String simpleName, boolean interfaceType,
            Set<String> superTypes, Set<String> annotations, boolean finalType, boolean springManaged) {}

    private record RawCall(SourceLocation location, String scope, String receiverType,
            String methodName, int argumentCount) {}

    private record RawMethod(
            MethodId id,
            SourceLocation location,
            Set<String> annotations,
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
            boolean topLevel
    ) {
        private boolean acceptsArity(int arity) {
            return arity >= minimumArity && arity <= maximumArity;
        }
    }

    private record MappedUnit(Path file, List<RawMethod> methods, List<TypeInfo> types,
            boolean producerListenerVisible) {}

    private record ParseBatch(List<MappedUnit> units, List<ParseFailure> failures) {}

    private record MappedProject(Map<MethodId, RawMethod> methods, Map<String, TypeInfo> types,
            List<ParseFailure> failures, boolean producerListenerVisible) {}

    private record Resolution(Optional<MethodId> target, ResolutionReason reason) {}
}
