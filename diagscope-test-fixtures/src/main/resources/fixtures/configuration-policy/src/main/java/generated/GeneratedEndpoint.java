package generated;

@interface GeneratedBoundary {
}

final class GeneratedEndpoint {
    @GeneratedBoundary
    void generated() {
        try {
            throw new IllegalStateException("generated");
        } catch (RuntimeException ignored) {
        }
    }
}
