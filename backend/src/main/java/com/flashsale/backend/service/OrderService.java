package com.flashsale.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.backend.domain.Order;
import com.flashsale.backend.domain.Outbox;
import com.flashsale.backend.domain.SaleEvent;
import com.flashsale.backend.domain.User;
import com.flashsale.backend.dto.event.OrderEvent;
import com.flashsale.backend.dto.request.CancelOrderRequest;
import com.flashsale.backend.dto.request.CreateOrderRequest;
import com.flashsale.backend.dto.response.OrderListResponse;
import com.flashsale.backend.dto.response.OrderResponse;
import com.flashsale.backend.exception.*;
import com.flashsale.backend.repository.OrderRepository;
import com.flashsale.backend.repository.OutboxRepository;
import com.flashsale.backend.repository.SaleEventRepository;
import com.flashsale.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final SaleEventRepository saleEventRepository;
    private final UserRepository userRepository;
    private final OutboxRepository outboxRepository;
    private final FlashSaleService flashSaleService;
    private final RedisLockService redisLockService;
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public OrderResponse createOrder(String email, String idempotencyKey, CreateOrderRequest request) {
        // 멱등성 체크 (락 외부 — Redis 원자 연산)
        String redisIdempotencyKey = "idempotency:" + idempotencyKey;
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(redisIdempotencyKey, idempotencyKey, 10, TimeUnit.MINUTES);
        if (!Boolean.TRUE.equals(isNew)) {
            throw new DuplicateOrderRequestException();
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        Long saleEventId = request.getSaleEventId();

        if (flashSaleService.isHalted(saleEventId)) {
            throw new SaleEventHaltedException(saleEventId);
        }

        SaleEvent saleEvent = saleEventRepository.findById(saleEventId)
                .orElseThrow(() -> new FlashSaleNotFoundException(saleEventId));

        // C: 도메인 메서드로 유효성 검증
        saleEvent.validateActive();
        validateMaxQuantityPerUser(user, saleEvent, request.getQuantity());

        // D: Redis DECR은 원자적 연산이므로 락 외부에서 수행
        int quantity = request.getQuantity();
        for (int i = 0; i < quantity; i++) {
            long remaining = flashSaleService.decrementStockInRedis(saleEventId);
            if (remaining < 0) {
                for (int j = 0; j <= i; j++) {
                    flashSaleService.incrementStockInRedis(saleEventId);
                }
                throw new InsufficientStockException(quantity, remaining + quantity);
            }
        }

        // D: 락은 DB 쓰기 구간만 보호
        String lockKey = "flash-sale:" + saleEventId;
        try {
            return redisLockService.executeWithLock(lockKey,
                    () -> createOrderInDb(user, saleEvent, quantity, idempotencyKey));
        } catch (RuntimeException e) {
            // DB 쓰기 실패 시 Redis 재고 복구
            for (int i = 0; i < quantity; i++) {
                flashSaleService.incrementStockInRedis(saleEventId);
            }
            throw e;
        }
    }

    @Transactional
    protected OrderResponse createOrderInDb(User user, SaleEvent saleEvent, int quantity, String idempotencyKey) {
        // C: 정적 팩토리 메서드로 Order 생성
        Order order = Order.create(user, saleEvent, quantity, idempotencyKey);
        Order saved = orderRepository.save(order);

        // C: 도메인 메서드로 재고 예약
        saleEvent.reserveStock(quantity);
        saleEventRepository.save(saleEvent);

        log.info("Order created: orderId={}, userId={}, saleEventId={}", saved.getId(), user.getId(), saleEvent.getId());

        // B: Kafka 직접 발행 대신 Outbox에 저장 (동일 트랜잭션)
        saveOutbox(saved, user, saleEvent, quantity);

        return OrderResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId, String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        Order order = orderRepository.findByIdAndUser(orderId, user)
                .orElseThrow(() -> new OrderNotFoundException(String.valueOf(orderId)));

        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderListResponse getMyOrders(String email, String cursor, int size, String statusStr) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        Order.OrderStatus status = null;
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                status = Order.OrderStatus.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException ignored) {
            }
        }

        Long cursorDecoded = decodeCursor(cursor);
        List<Order> orders = orderRepository.findByUserWithCursor(user, status, cursorDecoded,
                PageRequest.of(0, size + 1));

        boolean hasNext = orders.size() > size;
        if (hasNext) {
            orders = orders.subList(0, size);
        }

        String nextCursor = hasNext && !orders.isEmpty()
                ? encodeCursor(orders.get(orders.size() - 1).getId())
                : null;

        List<OrderListResponse.OrderItem> items = orders.stream()
                .map(o -> OrderListResponse.OrderItem.builder()
                        .orderId(o.getId())
                        .productName(o.getSaleEvent() != null && o.getSaleEvent().getProduct() != null
                                ? o.getSaleEvent().getProduct().getName() : null)
                        .status(o.getStatus().name())
                        .totalAmount(o.getTotalAmount())
                        .createdAt(o.getCreatedAt())
                        .build())
                .toList();

        return OrderListResponse.builder()
                .data(items)
                .nextCursor(nextCursor)
                .hasNext(hasNext)
                .build();
    }

    @Transactional
    public OrderResponse cancelOrder(Long orderId, String email, CancelOrderRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        Order order = orderRepository.findByIdAndUser(orderId, user)
                .orElseThrow(() -> new OrderNotFoundException(String.valueOf(orderId)));

        // C: 도메인 메서드로 취소 — 상태 검증 + 전환 캡슐화
        order.cancel();
        orderRepository.save(order);

        SaleEvent saleEvent = order.getSaleEvent();
        // C: 도메인 메서드로 재고 해제
        saleEvent.releaseStock(order.getQuantity());
        saleEventRepository.save(saleEvent);

        flashSaleService.incrementStockInRedis(saleEvent.getId());

        log.info("Order cancelled: orderId={}, userId={}", orderId, user.getId());

        return OrderResponse.from(order);
    }

    /**
     * Legacy method kept for backward compatibility with existing tests.
     */
    @Transactional
    public OrderResponse createOrder(String email, CreateOrderRequest request) {
        String idempotencyKey = UUID.randomUUID().toString();
        return createOrder(email, idempotencyKey, request);
    }

    /**
     * Legacy method kept for backward compatibility with existing tests.
     */
    @Transactional(readOnly = true)
    public List<OrderResponse> getMyOrders(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
        return orderRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(OrderResponse::from)
                .toList();
    }

    private void validateMaxQuantityPerUser(User user, SaleEvent saleEvent, int requestedQuantity) {
        Integer alreadyOrdered = orderRepository.sumQuantityByUserAndSaleEvent(user.getId(), saleEvent.getId());
        if (alreadyOrdered == null) {
            alreadyOrdered = 0;
        }
        int maxQuantityPerUser = Integer.MAX_VALUE;
        if (alreadyOrdered + requestedQuantity > maxQuantityPerUser) {
            throw new MaxQuantityExceededException(maxQuantityPerUser);
        }
    }

    private void saveOutbox(Order order, User user, SaleEvent saleEvent, int quantity) {
        OrderEvent event = OrderEvent.builder()
                .orderId(order.getId())
                .userId(user.getId())
                .username(user.getEmail())
                .saleEventId(saleEvent.getId())
                .productId(saleEvent.getProduct().getId())
                .productName(saleEvent.getProduct().getName())
                .quantity(quantity)
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus().name())
                .createdAt(LocalDateTime.now())
                .build();

        try {
            String payload = objectMapper.writeValueAsString(event);
            Outbox outbox = Outbox.builder()
                    .aggregateId(order.getId())
                    .aggregateType("Order")
                    .eventType("ORDER_CREATED")
                    .payload(payload)
                    .build();
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize OrderEvent for orderId={}: {}", order.getId(), e.getMessage());
            throw new RuntimeException("Outbox serialization failed", e);
        }
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
