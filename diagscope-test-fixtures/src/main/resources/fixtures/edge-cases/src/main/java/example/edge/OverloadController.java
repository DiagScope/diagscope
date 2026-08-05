package example.edge;

@RestController
public class OverloadController {
    private final OverloadService service;

    public OverloadController(OverloadService service) {
        this.service = service;
    }

    @PostMapping("/overloads")
    public String handle(String id) {
        return service.process(id) + service.process(id, 1) + service.process(id, "retry");
    }
}
