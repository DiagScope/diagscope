package example.observability;

public class MeterRegistry {
    public Counter counter(String name, String... tags) { return new Counter(); }
}
