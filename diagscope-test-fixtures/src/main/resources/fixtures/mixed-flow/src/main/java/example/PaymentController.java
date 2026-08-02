package example;

@RestController
public class PaymentController {
    private final PaymentService paymentService;
    public PaymentController(PaymentService paymentService) { this.paymentService = paymentService; }

    @PostMapping("/payments/{id}/capture")
    public boolean capture(String id) { return paymentService.capture(id); }
}
