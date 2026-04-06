package com.flashsale.backend.kafka;

import com.flashsale.backend.config.KafkaConfig;
import com.flashsale.backend.dto.event.OrderEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventProducer {

    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

    public void sendOrderCreatedEvent(OrderEvent event) {
        String key = String.valueOf(event.getOrderId());
        CompletableFuture<SendResult<String, OrderEvent>> future =
                kafkaTemplate.send(KafkaConfig.ORDER_CREATED_TOPIC, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to send order event for orderId={}: {}", key, ex.getMessage());
            } else {
                log.info("Order event sent successfully: orderId={}, partition={}, offset={}",
                        key,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
