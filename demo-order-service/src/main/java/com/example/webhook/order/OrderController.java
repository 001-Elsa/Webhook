package com.example.webhook.order;

import jakarta.validation.constraints.DecimalMin;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.Collection;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public Collection<OrderRecord> list() {
        return orderService.list().values();
    }

    @PostMapping
    public OrderRecord create(@RequestBody CreateOrderRequest request) {
        return orderService.create(request.amount());
    }

    @PostMapping("/{orderId}/pay")
    public OrderRecord pay(@PathVariable String orderId) {
        return orderService.transition(orderId, OrderStatus.PAID);
    }

    @PostMapping("/{orderId}/cancel")
    public OrderRecord cancel(@PathVariable String orderId) {
        return orderService.transition(orderId, OrderStatus.CANCELLED);
    }

    @PostMapping("/{orderId}/ship")
    public OrderRecord ship(@PathVariable String orderId) {
        return orderService.transition(orderId, OrderStatus.SHIPPED);
    }

    public record CreateOrderRequest(@DecimalMin("0.01") BigDecimal amount) {
    }
}
