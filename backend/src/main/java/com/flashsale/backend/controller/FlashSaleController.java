package com.flashsale.backend.controller;

import com.flashsale.backend.dto.request.CreateFlashSaleRequest;
import com.flashsale.backend.dto.request.HaltSaleRequest;
import com.flashsale.backend.dto.response.FlashSaleResponse;
import com.flashsale.backend.dto.response.StockStatusResponse;
import com.flashsale.backend.dto.response.StockSyncResponse;
import com.flashsale.backend.service.FlashSaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/sales")
@RequiredArgsConstructor
public class FlashSaleController {

    private final FlashSaleService flashSaleService;

    @GetMapping
    public ResponseEntity<List<FlashSaleResponse>> getAllSales() {
        return ResponseEntity.ok(flashSaleService.getAllFlashSales());
    }

    @GetMapping("/{id}")
    public ResponseEntity<FlashSaleResponse> getSale(@PathVariable Long id) {
        return ResponseEntity.ok(flashSaleService.getFlashSale(id));
    }

    @PostMapping
    public ResponseEntity<FlashSaleResponse> createFlashSale(@RequestBody CreateFlashSaleRequest request) {
        FlashSaleResponse response = flashSaleService.createFlashSale(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/halt")
    public ResponseEntity<FlashSaleResponse> haltFlashSale(
            @PathVariable Long id,
            @RequestBody HaltSaleRequest request) {
        FlashSaleResponse response = flashSaleService.haltFlashSale(id, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/stock")
    public ResponseEntity<StockStatusResponse> getStockStatus(@PathVariable Long id) {
        return ResponseEntity.ok(flashSaleService.getStockStatus(id));
    }

    @PostMapping("/{id}/sync")
    public ResponseEntity<StockSyncResponse> syncStock(@PathVariable Long id) {
        return ResponseEntity.ok(flashSaleService.syncStock(id));
    }
}
