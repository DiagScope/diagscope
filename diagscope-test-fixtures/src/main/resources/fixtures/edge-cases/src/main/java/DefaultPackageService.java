public class DefaultPackageService {
    public String read(String id) {
        try {
            return id.trim();
        } catch (RuntimeException exception) {
            exception.printStackTrace();
            return "";
        }
    }
}
