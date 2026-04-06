package com.flashsale.backend.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEvent {

    private Long orderId;
    private Long userId;
    private String username;
    private Long saleEventId;
    private Long productId;
    private String productName;
    private Integer quantity;
    private int totalAmount;
    private String status;
    private LocalDateTime createdAt;
}
