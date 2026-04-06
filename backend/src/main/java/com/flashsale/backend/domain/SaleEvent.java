package com.flashsale.backend.domain;

import com.flashsale.backend.exception.FlashSaleNotActiveException;
import com.flashsale.backend.exception.SaleEventHaltedException;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "sale_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SaleEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int totalStock;

    @Column(nullable = false)
    @Builder.Default
    private int reservedStock = 0;

    @Column(nullable = false)
    @Builder.Default
    private int soldStock = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    @Builder.Default
    private SaleEventStatus status = SaleEventStatus.SCHEDULED;

    @Column(name = "is_halted", nullable = false, columnDefinition = "TINYINT(1)")
    @Builder.Default
    private boolean isHalted = false;

    @Column(nullable = false)
    private LocalDateTime startsAt;

    @Column(nullable = false)
    private LocalDateTime endsAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    public enum SaleEventStatus {
        SCHEDULED, ACTIVE, ENDED, HALTED
    }

    public void validateActive() {
        if (this.status == SaleEventStatus.HALTED || this.isHalted) {
            throw new SaleEventHaltedException(this.id);
        }
        if (this.status == SaleEventStatus.ENDED) {
            throw new FlashSaleNotActiveException(this.id);
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(this.startsAt) || !now.isBefore(this.endsAt)) {
            throw new FlashSaleNotActiveException(this.id);
        }
    }

    public void reserveStock(int quantity) {
        this.reservedStock += quantity;
    }

    public void releaseStock(int quantity) {
        this.reservedStock = Math.max(0, this.reservedStock - quantity);
    }
}
