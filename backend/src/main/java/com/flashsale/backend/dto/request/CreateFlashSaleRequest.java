package com.flashsale.backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CreateFlashSaleRequest {

    private Long productId;
    private Integer totalStock;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
}
