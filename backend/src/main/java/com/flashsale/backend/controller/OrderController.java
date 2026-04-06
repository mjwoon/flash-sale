package com.flashsale.backend.controller;

import com.flashsale.backend.dto.request.CancelOrderRequest;
import com.flashsale.backend.dto.request.CreateOrderRequest;
import com.flashsale.backend.dto.response.OrderListResponse;
import com.flashsale.backend.dto.response.OrderResponse;
import com.flashsale.backend.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateOrderRequest request) {
        OrderResponse response = orderService.createOrder(
                userDetails.getUsername(), idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id) {
        OrderResponse response = orderService.getOrder(id, userDetails.getUsername());
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<OrderListResponse> getMyOrders(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        OrderListResponse response = orderService.getMyOrders(
                userDetails.getUsername(), cursor, size, status);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long id,
            @RequestBody CancelOrderRequest request) {
        OrderResponse response = orderService.cancelOrder(id, userDetails.getUsername(), request);
        return ResponseEntity.ok(response);
    }
}
