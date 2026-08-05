@RestController
public class DefaultPackageController {
    private final DefaultPackageService service = new DefaultPackageService();

    @GetMapping("/default-package")
    public String read(String id) {
        return service.read(id);
    }
}
