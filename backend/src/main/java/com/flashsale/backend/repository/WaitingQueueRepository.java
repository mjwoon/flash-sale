package com.flashsale.backend.repository;

import com.flashsale.backend.domain.WaitingQueue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WaitingQueueRepository extends JpaRepository<WaitingQueue, Long> {

    List<WaitingQueue> findBySaleEventIdAndStatus(Long saleEventId, WaitingQueue.QueueStatus status);

    Optional<WaitingQueue> findBySaleEventIdAndUserId(Long saleEventId, Long userId);
}
