package com.flashsale.backend.repository;

import com.flashsale.backend.domain.SaleEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SaleEventRepository extends JpaRepository<SaleEvent, Long> {

    @Query("SELECT se FROM SaleEvent se WHERE se.status = 'ACTIVE' AND se.startsAt <= :now AND se.endsAt > :now")
    List<SaleEvent> findActiveSales(@Param("now") LocalDateTime now);

    List<SaleEvent> findByStatus(SaleEvent.SaleEventStatus status);

    @Query("SELECT se FROM SaleEvent se WHERE se.product.id = :productId AND se.status IN ('ACTIVE', 'SCHEDULED')")
    Optional<SaleEvent> findActiveOrScheduledByProductId(@Param("productId") Long productId);
}
