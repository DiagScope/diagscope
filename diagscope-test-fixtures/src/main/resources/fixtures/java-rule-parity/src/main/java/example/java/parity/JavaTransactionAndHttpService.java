package example.java.parity;

@Service
class JavaTransactionAndHttpService {
    private final UserRepository userRepository;
    private final WebClient webClient;

    JavaTransactionAndHttpService(UserRepository userRepository, WebClient webClient) {
        this.userRepository = userRepository;
        this.webClient = webClient;
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
}
