package com.flashsale.backend.repository;

import com.flashsale.backend.domain.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {

    List<Outbox> findByStatusOrderByCreatedAtAsc(Outbox.OutboxStatus status);

    List<Outbox> findByAggregateTypeAndAggregateId(String aggregateType, Long aggregateId);
}
