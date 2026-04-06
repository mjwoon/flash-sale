package com.flashsale.backend.kafka;

import com.flashsale.backend.config.KafkaConfig;
import com.flashsale.backend.dto.event.OrderEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderEventConsumer {

    @KafkaListener(
            topics = KafkaConfig.ORDER_CREATED_TOPIC,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeOrderCreatedEvent(OrderEvent event) {
        log.info("[ORDER-EVENT] Received order-created event: orderId={}, userId={}, userEmail={}, " +
                        "saleEventId={}, productName={}, quantity={}, totalAmount={}, status={}",
                event.getOrderId(),
                event.getUserId(),
                event.getUsername(),
                event.getSaleEventId(),
                event.getProductName(),
                event.getQuantity(),
                event.getTotalAmount(),
                event.getStatus());

        processOrderEvent(event);
    }

    private void processOrderEvent(OrderEvent event) {
        log.info("[ORDER-EVENT] Processing completed for orderId={}", event.getOrderId());
    }
}
