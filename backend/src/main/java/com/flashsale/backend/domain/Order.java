package com.flashsale.backend.domain;

import com.flashsale.backend.exception.OrderAlreadyCancelledException;
import com.flashsale.backend.exception.OrderNotCancellableException;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sale_event_id", nullable = false)
    private SaleEvent saleEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    @Builder.Default
    private int quantity = 1;

    @Column(nullable = false)
    private int totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum OrderStatus {
        PENDING, PAID, FAILED, CANCELLED, EXPIRED
    }

    public static Order create(User user, SaleEvent saleEvent, int quantity, String idempotencyKey) {
        return Order.builder()
                .user(user)
                .saleEvent(saleEvent)
                .product(saleEvent.getProduct())
                .quantity(quantity)
                .totalAmount(saleEvent.getProduct().getPrice() * quantity)
                .status(OrderStatus.PENDING)
                .idempotencyKey(idempotencyKey)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
    }

    public void cancel() {
        if (this.status == OrderStatus.CANCELLED) {
            throw new OrderAlreadyCancelledException(String.valueOf(this.id));
        }
        if (this.status != OrderStatus.PENDING && this.status != OrderStatus.PAID) {
            throw new OrderNotCancellableException(String.valueOf(this.id), this.status.name());
        }
        this.status = OrderStatus.CANCELLED;
    }

    public void markExpired() {
        this.status = OrderStatus.EXPIRED;
    }
}
