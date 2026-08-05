package example.aop;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final ReportBuilder reportBuilder;

    public OrderController(OrderService orderService, ReportBuilder reportBuilder) {
        this.orderService = orderService;
        this.reportBuilder = reportBuilder;
    }

    @PostMapping("/{id}/confirm")
    public String confirm(String id) {
        orderService.confirm(id);
        return reportBuilder.build(id);
    }
}
