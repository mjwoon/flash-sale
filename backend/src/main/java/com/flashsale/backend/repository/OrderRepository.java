package com.flashsale.backend.repository;

import com.flashsale.backend.domain.Order;
import com.flashsale.backend.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserOrderByCreatedAtDesc(User user);

    Optional<Order> findByIdAndUser(Long id, User user);

    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT o FROM Order o WHERE o.user = :user AND (:status IS NULL OR o.status = :status) " +
            "AND (:cursor IS NULL OR o.id < :cursor) ORDER BY o.id DESC")
    List<Order> findByUserWithCursor(@Param("user") User user,
                                     @Param("status") Order.OrderStatus status,
                                     @Param("cursor") Long cursor,
                                     org.springframework.data.domain.Pageable pageable);

    @Query("SELECT COALESCE(SUM(o.quantity), 0) FROM Order o WHERE o.user.id = :userId AND o.saleEvent.id = :saleEventId AND o.status != 'CANCELLED'")
    Integer sumQuantityByUserAndSaleEvent(@Param("userId") Long userId, @Param("saleEventId") Long saleEventId);

    @Query("SELECT COALESCE(SUM(o.quantity), 0) FROM Order o WHERE o.saleEvent.id = :saleEventId AND o.status = 'PENDING'")
    Integer sumReservedStockBySaleEvent(@Param("saleEventId") Long saleEventId);

    @Query("SELECT COALESCE(SUM(o.quantity), 0) FROM Order o WHERE o.saleEvent.id = :saleEventId AND o.status = 'PAID'")
    Integer sumSoldStockBySaleEvent(@Param("saleEventId") Long saleEventId);
}
