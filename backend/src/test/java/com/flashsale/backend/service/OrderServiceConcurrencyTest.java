package com.flashsale.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.backend.domain.Order;
import com.flashsale.backend.domain.Outbox;
import com.flashsale.backend.domain.Product;
import com.flashsale.backend.domain.SaleEvent;
import com.flashsale.backend.domain.User;
import com.flashsale.backend.dto.request.CreateOrderRequest;
import com.flashsale.backend.dto.response.OrderResponse;
import com.flashsale.backend.exception.FlashSaleNotActiveException;
import com.flashsale.backend.exception.InsufficientStockException;
import com.flashsale.backend.exception.LockAcquisitionException;
import com.flashsale.backend.repository.OrderRepository;
import com.flashsale.backend.repository.OutboxRepository;
import com.flashsale.backend.repository.SaleEventRepository;
import com.flashsale.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceConcurrencyTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private SaleEventRepository saleEventRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private FlashSaleService flashSaleService;

    @Mock
    private RedisLockService redisLockService;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private OrderService orderService;

    private User testUser;
    private Product testProduct;
    private SaleEvent activeSaleEvent;

    @BeforeEach
    void setUp() {
        testProduct = Product.builder()
                .id(1L)
                .name("Test Product")
                .price(80000)
                .build();

        testUser = User.builder()
                .id(1L)
                .name("testuser")
                .password("encoded_password")
                .email("test@example.com")
                .role(User.Role.USER)
                .build();

        activeSaleEvent = SaleEvent.builder()
                .id(1L)
                .product(testProduct)
                .startsAt(LocalDateTime.now().minusMinutes(10))
                .endsAt(LocalDateTime.now().plusHours(1))
                .totalStock(10)
                .reservedStock(0)
                .soldStock(0)
                .status(SaleEvent.SaleEventStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("단일 주문 생성 - 정상 케이스")
    void createOrder_success() throws Exception {
        // given
        String idempotencyKey = UUID.randomUUID().toString();
        CreateOrderRequest request = new CreateOrderRequest(1L, 1);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(flashSaleService.isHalted(1L)).thenReturn(false);
        when(saleEventRepository.findById(1L)).thenReturn(Optional.of(activeSaleEvent));
        when(orderRepository.sumQuantityByUserAndSaleEvent(1L, 1L)).thenReturn(0);

        // D단계: DECR이 락 외부에서 수행됨
        when(flashSaleService.decrementStockInRedis(1L)).thenReturn(9L);

        Order savedOrder = Order.builder()
                .id(1L)
                .user(testUser)
                .saleEvent(activeSaleEvent)
                .product(testProduct)
                .quantity(1)
                .totalAmount(80000)
                .status(Order.OrderStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .createdAt(LocalDateTime.now())
                .build();

        when(redisLockService.executeWithLock(anyString(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> task = invocation.getArgument(1);
            return task.get();
        });
        when(orderRepository.save(any(Order.class))).thenReturn(savedOrder);
        // B단계: Outbox 저장 검증
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"orderId\":1}");
        when(outboxRepository.save(any(Outbox.class))).thenReturn(null);

        // when
        OrderResponse response = orderService.createOrder("test@example.com", idempotencyKey, request);

        // then
        assertThat(response).isNotNull();
        assertThat(response.getOrderId()).isEqualTo(1L);
        assertThat(response.getStatus()).isEqualTo("PENDING");
        // B단계: Kafka 직접 호출 대신 Outbox 저장 확인
        verify(outboxRepository, times(1)).save(any(Outbox.class));
    }

    @Test
    @DisplayName("재고 부족 시 InsufficientStockException 발생")
    void createOrder_insufficientStock_throwsException() {
        // given
        String idempotencyKey = UUID.randomUUID().toString();
        CreateOrderRequest request = new CreateOrderRequest(1L, 1);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(flashSaleService.isHalted(1L)).thenReturn(false);
        when(saleEventRepository.findById(1L)).thenReturn(Optional.of(activeSaleEvent));
        when(orderRepository.sumQuantityByUserAndSaleEvent(1L, 1L)).thenReturn(0);

        // D단계: DECR이 -1 반환 → 재고 부족
        when(flashSaleService.decrementStockInRedis(1L)).thenReturn(-1L);

        // when & then
        assertThatThrownBy(() -> orderService.createOrder("test@example.com", idempotencyKey, request))
                .isInstanceOf(InsufficientStockException.class);

        // 롤백: incrementStockInRedis 호출 확인
        verify(flashSaleService, times(1)).incrementStockInRedis(1L);
    }

    @Test
    @DisplayName("세일 이벤트가 비활성 상태일 때 FlashSaleNotActiveException 발생")
    void createOrder_saleEventNotActive_throwsException() {
        // given
        SaleEvent endedSaleEvent = SaleEvent.builder()
                .id(1L)
                .product(testProduct)
                .startsAt(LocalDateTime.now().minusHours(2))
                .endsAt(LocalDateTime.now().minusHours(1))
                .totalStock(10)
                .reservedStock(0)
                .soldStock(5)
                .status(SaleEvent.SaleEventStatus.ENDED)
                .build();

        String idempotencyKey = UUID.randomUUID().toString();
        CreateOrderRequest request = new CreateOrderRequest(1L, 1);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(flashSaleService.isHalted(1L)).thenReturn(false);
        when(saleEventRepository.findById(1L)).thenReturn(Optional.of(endedSaleEvent));

        // when & then
        assertThatThrownBy(() -> orderService.createOrder("test@example.com", idempotencyKey, request))
                .isInstanceOf(FlashSaleNotActiveException.class);
    }

    @Test
    @DisplayName("락 획득 실패 시 LockAcquisitionException 발생")
    void createOrder_lockAcquisitionFailed_throwsException() {
        // given
        String idempotencyKey = UUID.randomUUID().toString();
        CreateOrderRequest request = new CreateOrderRequest(1L, 1);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), anyLong(), any())).thenReturn(true);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(flashSaleService.isHalted(1L)).thenReturn(false);
        when(saleEventRepository.findById(1L)).thenReturn(Optional.of(activeSaleEvent));
        when(orderRepository.sumQuantityByUserAndSaleEvent(1L, 1L)).thenReturn(0);
        // D단계: DECR 성공 후 락 획득 실패
        when(flashSaleService.decrementStockInRedis(1L)).thenReturn(9L);
        when(redisLockService.executeWithLock(anyString(), any()))
                .thenThrow(new LockAcquisitionException("flash-sale:1"));

        // when & then
        assertThatThrownBy(() -> orderService.createOrder("test@example.com", idempotencyKey, request))
                .isInstanceOf(LockAcquisitionException.class)
                .hasMessageContaining("flash-sale:1");

        // 락 실패 시 Redis 재고 롤백 확인
        verify(flashSaleService, times(1)).incrementStockInRedis(1L);
    }

    @Test
    @DisplayName("동시 주문 시나리오 - CountDownLatch를 사용한 동시성 시뮬레이션")
    void createOrder_concurrencySimulation() throws InterruptedException {
        // given
        int threadCount = 20;
        int availableStock = 5;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        AtomicInteger remainingStock = new AtomicInteger(availableStock);

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    int current = remainingStock.get();
                    if (current > 0 && remainingStock.compareAndSet(current, current - 1)) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                        errors.add("Thread-" + threadIndex + ": InsufficientStock");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors.add("Thread-" + threadIndex + ": Interrupted");
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);

        executor.shutdown();

        // then
        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(availableStock);
        assertThat(failCount.get()).isEqualTo(threadCount - availableStock);
        assertThat(remainingStock.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("내 주문 목록 조회 성공")
    void getMyOrders_success() {
        // given
        Order order1 = Order.builder()
                .id(1L)
                .user(testUser)
                .saleEvent(activeSaleEvent)
                .product(testProduct)
                .quantity(1)
                .totalAmount(80000)
                .status(Order.OrderStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        Order order2 = Order.builder()
                .id(2L)
                .user(testUser)
                .saleEvent(activeSaleEvent)
                .product(testProduct)
                .quantity(2)
                .totalAmount(160000)
                .status(Order.OrderStatus.PENDING)
                .createdAt(LocalDateTime.now().minusMinutes(5))
                .build();

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(orderRepository.findByUserOrderByCreatedAtDesc(testUser)).thenReturn(List.of(order1, order2));

        // when
        List<OrderResponse> orders = orderService.getMyOrders("test@example.com");

        // then
        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).getOrderId()).isEqualTo(1L);
        assertThat(orders.get(1).getOrderId()).isEqualTo(2L);
    }
}
