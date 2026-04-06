package com.flashsale.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDetailResponse {

    private Long id;
    private String name;
    private String description;
    private int price;
    private SaleEventDetail saleEvent;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SaleEventDetail {
        private Long id;
        private Integer totalStock;
        private Integer reservedStock;
        private Integer soldStock;
        private String status;
        private LocalDateTime startsAt;
        private LocalDateTime endsAt;
    }
}
