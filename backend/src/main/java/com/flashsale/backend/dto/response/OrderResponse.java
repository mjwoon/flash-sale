package com.flashsale.backend.dto.response;

import com.flashsale.backend.domain.Order;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class OrderResponse {

    private Long orderId;
    private String idempotencyKey;
    private int totalAmount;
    private String status;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private PaymentInfo payment;
    private ProductInfo product;

    @Getter
    @Builder
    public static class PaymentInfo {
        private String checkoutUrl;
    }

    @Getter
    @Builder
    public static class ProductInfo {
        private String name;
    }

    public static OrderResponse from(Order order) {
        String productName = order.getSaleEvent() != null && order.getSaleEvent().getProduct() != null
                ? order.getSaleEvent().getProduct().getName()
                : (order.getProduct() != null ? order.getProduct().getName() : null);
        return OrderResponse.builder()
                .orderId(order.getId())
                .idempotencyKey(order.getIdempotencyKey())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .expiresAt(order.getExpiresAt())
                .createdAt(order.getCreatedAt())
                .payment(PaymentInfo.builder().checkoutUrl(null).build())
                .product(productName != null ? ProductInfo.builder().name(productName).build() : null)
                .build();
    }
}
