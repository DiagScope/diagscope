package example.java.parity;

@Service
class JavaTransactionAndHttpService {
    private final UserRepository userRepository;
    private final EntityManager entityManager;
    private final WebClient webClient;
    private final Map<String, Object> cache;

    JavaTransactionAndHttpService(UserRepository userRepository, EntityManager entityManager,
                                  WebClient webClient, Map<String, Object> cache) {
        this.userRepository = userRepository;
        this.entityManager = entityManager;
        this.webClient = webClient;
        this.cache = cache;
    }

    // TRIGGERS: MISSING_TRANSACTION_ANNOTATION — write op with no @Transactional
    void saveWithoutTransaction(Object entity) {
        userRepository.save(entity);
    }

    // SAFE: @Transactional provides the boundary
    @Transactional
    void saveWithTransaction(Object entity) {
        userRepository.save(entity);
    }

    // TRIGGERS: READONLY_TRANSACTION_WRITE — readOnly transaction performs a write
    @Transactional(readOnly = true)
    void saveInsideReadOnlyTransaction(Object entity) {
        userRepository.save(entity);
    }

    // TRIGGERS: ENTITY_MANAGER_FIND_DEREFERENCE — EntityManager.find may return null
    String findAndDereference(String id) {
        Object order = entityManager.find(JavaOrderEntity.class, id);
        return order.toString();
    }

    // TRIGGERS: HTTP_TIMEOUT_NOT_SET — assigns to 'response' (HTTP hint) then blocks without timeout
    String callWithoutTimeout(String uri) {
        Mono<String> response = webClient.get(uri);
        return response.block();
    }

    // SAFE: timeout is configured before blocking
    String callWithTimeout(String uri) {
        Mono<String> response = webClient.get(uri);
        return response.timeout("PT5S").block();
    }

    // TRIGGERS: OPTIONAL_OR_ELSE_NULL — orElse(null) propagates null instead of enforcing presence
    Object lookupOrNull(String key) {
        return Optional.ofNullable(cache.get(key)).orElse(null);
    }

    // SAFE: orElse with a real fallback
    Object lookupWithFallback(String key) {
        return Optional.ofNullable(cache.get(key)).orElse(new Object());
    }

    // TRIGGERS: MAP_GET_DEREFERENCED_WITHOUT_CHECK — Map.get result used without null guard
    String getAndDereference(String key) {
        Object value = cache.get(key);
        return value.toString();
    }

    // SAFE: getOrDefault provides a non-null fallback
    String getWithDefault(String key) {
        Object value = cache.getOrDefault(key, "");
        return value.toString();
    }

    // TRIGGERS: TRANSACTION_ISOLATION_DANGEROUS — READ_UNCOMMITTED enables dirty reads
    @Transactional(isolation = Isolation.READ_UNCOMMITTED)
    List<Object> fetchWithDirtyReads() {
        return userRepository.findAll();
    }

    // SAFE: default isolation (READ_COMMITTED on most databases)
    @Transactional
    List<Object> fetchWithDefaultIsolation() {
        return userRepository.findAll();
    }

    // TRIGGERS: JPA_BATCH_LOOP_WITHOUT_FLUSH_CLEAR — persist in loop without flush/clear
    @Transactional
    void importEntities(List<Object> entities) {
        for (Object entity : entities) {
            entityManager.persist(entity);
        }
    }

    // SAFE: flush + clear every batch to bound the persistence context
    @Transactional
    void importEntitiesSafe(List<Object> entities) {
        int count = 0;
        for (Object entity : entities) {
            entityManager.persist(entity);
            if (++count % 50 == 0) {
                entityManager.flush();
                entityManager.clear();
            }
        }
        entityManager.flush();
        entityManager.clear();
    }

    // REQUIRES_NEW: each invocation suspends the outer transaction and opens a fresh one
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void processItemInNewTransaction(Object item) {
        userRepository.save(item);
    }

    // SAFE: the loop lives inside the REQUIRES_NEW method itself — no per-iteration transaction boundary
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void processItemBatch(List<Object> items) {
        for (Object item : items) {
            userRepository.save(item);
        }
    }
}
