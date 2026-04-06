package com.flashsale.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.backend.config.KafkaConfig;
import com.flashsale.backend.domain.Outbox;
import com.flashsale.backend.dto.event.OrderEvent;
import com.flashsale.backend.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private static final int MAX_RETRY = 3;
    private static final long SEND_TIMEOUT_SEC = 5L;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void relay() {
        List<Outbox> pending = outboxRepository.findByStatusOrderByCreatedAtAsc(Outbox.OutboxStatus.PENDING);
        for (Outbox outbox : pending) {
            try {
                OrderEvent event = objectMapper.readValue(outbox.getPayload(), OrderEvent.class);
                String key = String.valueOf(event.getOrderId());

                // 동기적으로 Kafka 발행 — 실패 시 예외 발생
                kafkaTemplate.send(KafkaConfig.ORDER_CREATED_TOPIC, key, event)
                        .get(SEND_TIMEOUT_SEC, TimeUnit.SECONDS);

                outbox.setStatus(Outbox.OutboxStatus.PUBLISHED);
                outbox.setPublishedAt(LocalDateTime.now());
                log.info("Outbox relayed: id={}, orderId={}", outbox.getId(), event.getOrderId());

            } catch (Exception e) {
                outbox.setRetryCount(outbox.getRetryCount() + 1);
                if (outbox.getRetryCount() >= MAX_RETRY) {
                    outbox.setStatus(Outbox.OutboxStatus.FAILED);
                    log.error("Outbox permanently failed: id={}, retries={}", outbox.getId(), outbox.getRetryCount());
                } else {
                    log.warn("Outbox relay failed (retry {}/{}): id={}, error={}",
                            outbox.getRetryCount(), MAX_RETRY, outbox.getId(), e.getMessage());
                }
            }
            outboxRepository.save(outbox);
        }
    }
}
