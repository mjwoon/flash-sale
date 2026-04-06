package com.flashsale.backend.controller;

import com.flashsale.backend.dto.response.ProductDetailResponse;
import com.flashsale.backend.dto.response.ProductListResponse;
import com.flashsale.backend.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ResponseEntity<ProductListResponse> getProducts(
            @RequestParam(required = false, defaultValue = "ALL") String saleStatus,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "20") int size) {
        return ResponseEntity.ok(productService.getProducts(saleStatus, cursor, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDetailResponse> getProduct(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getProductDetail(id));
    }
}
