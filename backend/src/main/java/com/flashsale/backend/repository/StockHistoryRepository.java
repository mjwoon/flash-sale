package com.flashsale.backend.repository;

import com.flashsale.backend.domain.StockHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockHistoryRepository extends JpaRepository<StockHistory, Long> {

    List<StockHistory> findBySaleEventIdOrderByCreatedAtDesc(Long saleEventId);

    List<StockHistory> findByOrderId(Long orderId);
}
