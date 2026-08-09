package example.context;

@RestController
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @GetMapping("/orders")
    public String orders(String orderId) {
        service.placeOrder(orderId);
        service.dispatchWithoutContext(orderId);
        service.dispatchWithContext(orderId);
        service.leakContext(orderId);
        service.logAndRethrow(orderId);
        service.logAndWrapWithoutCause(orderId);
        service.wrapWithCause(orderId);
        service.loadQuietly(orderId);
        return service.loadTimed(orderId);
    }
}
