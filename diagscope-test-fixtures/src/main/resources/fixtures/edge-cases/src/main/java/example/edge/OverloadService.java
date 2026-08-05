package example.edge;

public class OverloadService {
    public String process(String id) {
        try {
            return id;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    public String process(String id, int attempts) {
        return id + attempts;
    }

    public String process(String id, String mode) {
        System.out.println("processing " + id + " in mode " + mode);
        return mode;
    }
}
