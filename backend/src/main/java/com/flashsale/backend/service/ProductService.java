package com.flashsale.backend.service;

import com.flashsale.backend.domain.Product;
import com.flashsale.backend.domain.SaleEvent;
import com.flashsale.backend.dto.request.CreateProductRequest;
import com.flashsale.backend.dto.response.ProductDetailResponse;
import com.flashsale.backend.dto.response.ProductListResponse;
import com.flashsale.backend.dto.response.ProductResponse;
import com.flashsale.backend.exception.ProductHasActiveOrdersException;
import com.flashsale.backend.exception.ProductNotFoundException;
import com.flashsale.backend.repository.OrderRepository;
import com.flashsale.backend.repository.ProductRepository;
import com.flashsale.backend.repository.SaleEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final SaleEventRepository saleEventRepository;
    private final OrderRepository orderRepository;

    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {
        Product product = Product.builder()
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice())
                .isDeleted(false)
                .build();

        Product saved = productRepository.save(product);
        log.info("Product created: id={}, name={}", saved.getId(), saved.getName());
        return ProductResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ProductListResponse getProducts(String saleStatus, String cursor, int size) {
        Long cursorId = decodeCursor(cursor);
        List<Product> products = productRepository.findActiveProductsWithCursor(cursorId,
                PageRequest.of(0, size + 1));

        boolean hasNext = products.size() > size;
        if (hasNext) {
            products = products.subList(0, size);
        }

        String nextCursor = hasNext && !products.isEmpty()
                ? encodeCursor(products.get(products.size() - 1).getId())
                : null;

        List<ProductListResponse.ProductItem> items = products.stream()
                .map(p -> buildProductItem(p, saleStatus))
                .filter(item -> item != null)
                .toList();

        return ProductListResponse.builder()
                .data(items)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse getProductDetail(Long id) {
        Product product = productRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        Optional<SaleEvent> saleOpt = saleEventRepository.findActiveOrScheduledByProductId(id);

        ProductDetailResponse.SaleEventDetail saleEventDetail = saleOpt.map(sale -> {
            Integer reserved = orderRepository.sumReservedStockBySaleEvent(sale.getId());
            Integer sold = orderRepository.sumSoldStockBySaleEvent(sale.getId());
            return ProductDetailResponse.SaleEventDetail.builder()
                    .id(sale.getId())
                    .totalStock(sale.getTotalStock())
                    .reservedStock(reserved != null ? reserved : 0)
                    .soldStock(sold != null ? sold : 0)
                    .status(sale.getStatus().name())
                    .startsAt(sale.getStartsAt())
                    .endsAt(sale.getEndsAt())
                    .build();
        }).orElse(null);

        return ProductDetailResponse.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .saleEvent(saleEventDetail)
                .build();
    }

    @Transactional
    public void deleteProduct(Long id) {
        Product product = productRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ProductNotFoundException(id));

        Optional<SaleEvent> activeSale = saleEventRepository.findActiveOrScheduledByProductId(id);
        if (activeSale.isPresent()) {
            Integer pendingOrders = orderRepository.sumReservedStockBySaleEvent(activeSale.get().getId());
            if (pendingOrders != null && pendingOrders > 0) {
                throw new ProductHasActiveOrdersException(id);
            }
        }

        product.setDeleted(true);
        productRepository.save(product);
        log.info("Product soft deleted: id={}", id);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getAllProducts() {
        return productRepository.findAll().stream()
                .filter(p -> !p.isDeleted())
                .map(ProductResponse::from)
                .toList();
    }

    private ProductListResponse.ProductItem buildProductItem(Product product, String saleStatus) {
        Optional<SaleEvent> saleOpt = saleEventRepository.findActiveOrScheduledByProductId(product.getId());

        ProductListResponse.SaleEventSummary saleEventSummary = null;
        String itemSaleStatus = "NONE";

        if (saleOpt.isPresent()) {
            SaleEvent sale = saleOpt.get();
            itemSaleStatus = sale.getStatus().name();
            int remainingStock = sale.getTotalStock() - sale.getReservedStock() - sale.getSoldStock();
            saleEventSummary = ProductListResponse.SaleEventSummary.builder()
                    .status(sale.getStatus().name())
                    .remainingStock(remainingStock)
                    .startsAt(sale.getStartsAt())
                    .build();
        }

        if (saleStatus != null && !"ALL".equalsIgnoreCase(saleStatus)) {
            if (!itemSaleStatus.equalsIgnoreCase(saleStatus)) {
                return null;
            }
        }

        return ProductListResponse.ProductItem.builder()
                .id(product.getId())
                .name(product.getName())
                .price(product.getPrice())
                .saleEvent(saleEventSummary)
                .build();
    }

    private Long decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(cursor));
            return Long.parseLong(decoded);
        } catch (Exception e) {
            return null;
        }
    }

    private String encodeCursor(Long id) {
        return Base64.getEncoder().encodeToString(String.valueOf(id).getBytes());
    }
}
