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
public class StockSyncResponse {

    private Integer syncedStock;
    private Long previousRedisStock;
    private LocalDateTime syncedAt;
}
