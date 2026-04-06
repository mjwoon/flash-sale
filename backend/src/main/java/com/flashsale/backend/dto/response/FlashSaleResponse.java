package com.flashsale.backend.dto.response;

import com.flashsale.backend.domain.SaleEvent;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class FlashSaleResponse {

    private Long id;
    private Long productId;
    private Integer totalStock;
    private String status;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;

    public static FlashSaleResponse from(SaleEvent saleEvent) {
        return FlashSaleResponse.builder()
                .id(saleEvent.getId())
                .productId(saleEvent.getProduct().getId())
                .totalStock(saleEvent.getTotalStock())
                .status(saleEvent.getStatus().name())
                .startsAt(saleEvent.getStartsAt())
                .endsAt(saleEvent.getEndsAt())
                .build();
    }
}
