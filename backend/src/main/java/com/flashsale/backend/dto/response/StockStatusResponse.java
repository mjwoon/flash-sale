package com.flashsale.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockStatusResponse {

    private Integer totalStock;
    private Integer reservedStock;
    private Integer soldStock;
    private Integer availableStock;
    private Long redisStock;
    private boolean isConsistent;
}
