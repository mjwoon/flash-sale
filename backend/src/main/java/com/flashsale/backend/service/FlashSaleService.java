package com.flashsale.backend.service;

import com.flashsale.backend.domain.Product;
import com.flashsale.backend.domain.SaleEvent;
import com.flashsale.backend.dto.request.CreateFlashSaleRequest;
import com.flashsale.backend.dto.request.HaltSaleRequest;
import com.flashsale.backend.dto.response.FlashSaleResponse;
import com.flashsale.backend.dto.response.StockStatusResponse;
import com.flashsale.backend.dto.response.StockSyncResponse;
import com.flashsale.backend.exception.FlashSaleNotFoundException;
import com.flashsale.backend.exception.ProductNotFoundException;
import com.flashsale.backend.exception.SaleEventAlreadyExistsException;
import com.flashsale.backend.repository.OrderRepository;
import com.flashsale.backend.repository.ProductRepository;
import com.flashsale.backend.repository.SaleEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlashSaleService {

    public static final String STOCK_KEY_PREFIX = "flash-sale:stock:";
    public static final String HALT_KEY_PREFIX = "sale:halted:";

    private final SaleEventRepository saleEventRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @Transactional
    public FlashSaleResponse createFlashSale(CreateFlashSaleRequest request) {
        Product product = productRepository.findByIdAndIsDeletedFalse(request.getProductId())
                .orElseThrow(() -> new ProductNotFoundException(request.getProductId()));

        saleEventRepository.findActiveOrScheduledByProductId(request.getProductId())
                .ifPresent(existing -> {
                    throw new SaleEventAlreadyExistsException(request.getProductId());
                });

        SaleEvent saleEvent = SaleEvent.builder()
                .product(product)
                .startsAt(request.getStartsAt())
                .endsAt(request.getEndsAt())
                .totalStock(request.getTotalStock())
                .reservedStock(0)
                .soldStock(0)
                .status(SaleEvent.SaleEventStatus.SCHEDULED)
                .build();

        SaleEvent saved = saleEventRepository.save(saleEvent);
        initializeStockInRedis(saved.getId(), saved.getTotalStock());
        log.info("SaleEvent created: id={}, productId={}", saved.getId(), product.getId());
        return FlashSaleResponse.from(saved);
    }

    @Transactional
    public FlashSaleResponse haltFlashSale(Long id, HaltSaleRequest request) {
        SaleEvent saleEvent = saleEventRepository.findById(id)
                .orElseThrow(() -> new FlashSaleNotFoundException(id));

        saleEvent.setStatus(SaleEvent.SaleEventStatus.HALTED);
        saleEvent.setHalted(true);
        saleEventRepository.save(saleEvent);

        String haltKey = HALT_KEY_PREFIX + id;
        redisTemplate.opsForValue().set(haltKey, "1");

        log.info("SaleEvent halted: id={}", id);
        return FlashSaleResponse.from(saleEvent);
    }

    @Transactional(readOnly = true)
    public StockStatusResponse getStockStatus(Long id) {
        SaleEvent saleEvent = saleEventRepository.findById(id)
                .orElseThrow(() -> new FlashSaleNotFoundException(id));

        int totalStock = saleEvent.getTotalStock();
        int reservedStock = saleEvent.getReservedStock();
        int soldStock = saleEvent.getSoldStock();
        int availableStock = totalStock - reservedStock - soldStock;

        Long redisStock = getStockFromRedis(id);

        return StockStatusResponse.builder()
                .totalStock(totalStock)
                .reservedStock(reservedStock)
                .soldStock(soldStock)
                .availableStock(availableStock)
                .redisStock(redisStock)
                .isConsistent(availableStock == redisStock.intValue())
                .build();
    }

    @Transactional
    public StockSyncResponse syncStock(Long id) {
        SaleEvent saleEvent = saleEventRepository.findById(id)
                .orElseThrow(() -> new FlashSaleNotFoundException(id));

        // Orders 테이블 기준으로 집계해서 DB 컬럼과 Redis 동기화
        Integer reservedFromOrders = orderRepository.sumReservedStockBySaleEvent(id);
        Integer soldFromOrders = orderRepository.sumSoldStockBySaleEvent(id);
        if (reservedFromOrders == null) reservedFromOrders = 0;
        if (soldFromOrders == null) soldFromOrders = 0;

        saleEvent.setReservedStock(reservedFromOrders);
        saleEvent.setSoldStock(soldFromOrders);
        saleEventRepository.save(saleEvent);

        int syncedStock = saleEvent.getTotalStock() - reservedFromOrders - soldFromOrders;

        Long previousRedisStock = getStockFromRedis(id);
        String key = STOCK_KEY_PREFIX + id;
        redisTemplate.opsForValue().set(key, String.valueOf(syncedStock));

        log.info("Stock synced for saleEventId={}: previousRedis={}, synced={}", id, previousRedisStock, syncedStock);

        return StockSyncResponse.builder()
                .syncedStock(syncedStock)
                .previousRedisStock(previousRedisStock)
                .syncedAt(LocalDateTime.now())
                .build();
    }

    @Transactional(readOnly = true)
    public List<FlashSaleResponse> getAllFlashSales() {
        return saleEventRepository.findAll().stream()
                .map(FlashSaleResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public FlashSaleResponse getFlashSale(Long id) {
        SaleEvent saleEvent = saleEventRepository.findById(id)
                .orElseThrow(() -> new FlashSaleNotFoundException(id));
        ensureStockCached(saleEvent);
        return FlashSaleResponse.from(saleEvent);
    }

    @Transactional
    public List<FlashSaleResponse> getActiveFlashSales() {
        List<SaleEvent> actives = saleEventRepository.findActiveSales(LocalDateTime.now());
        actives.forEach(this::ensureStockCached);
        return actives.stream().map(FlashSaleResponse::from).toList();
    }

    public Long getStockFromRedis(Long saleEventId) {
        String key = STOCK_KEY_PREFIX + saleEventId;
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            SaleEvent saleEvent = saleEventRepository.findById(saleEventId)
                    .orElseThrow(() -> new FlashSaleNotFoundException(saleEventId));
            int available = saleEvent.getTotalStock() - saleEvent.getReservedStock() - saleEvent.getSoldStock();
            initializeStockInRedis(saleEventId, available);
            return (long) available;
        }
        return Long.parseLong(value);
    }

    public Long decrementStockInRedis(Long saleEventId) {
        String key = STOCK_KEY_PREFIX + saleEventId;
        return redisTemplate.opsForValue().decrement(key);
    }

    public void incrementStockInRedis(Long saleEventId) {
        String key = STOCK_KEY_PREFIX + saleEventId;
        redisTemplate.opsForValue().increment(key);
    }

    public boolean isHalted(Long saleEventId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(HALT_KEY_PREFIX + saleEventId));
    }

    private void initializeStockInRedis(Long saleEventId, int quantity) {
        String key = STOCK_KEY_PREFIX + saleEventId;
        redisTemplate.opsForValue().set(key, String.valueOf(quantity));
        log.debug("Stock initialized in Redis: key={}, quantity={}", key, quantity);
    }

    private void ensureStockCached(SaleEvent saleEvent) {
        String key = STOCK_KEY_PREFIX + saleEvent.getId();
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            int available = saleEvent.getTotalStock() - saleEvent.getReservedStock() - saleEvent.getSoldStock();
            initializeStockInRedis(saleEvent.getId(), available);
        }
    }
}
