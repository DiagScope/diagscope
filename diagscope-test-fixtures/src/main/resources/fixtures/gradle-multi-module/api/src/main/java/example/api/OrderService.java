package example.api;

public class OrderService {
    public String submit(String payload) {
        try {
            return process(payload);
        } catch (RuntimeException exception) {
            return "rejected";
        }
    }

    private String process(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
        return "accepted";
    }
}
