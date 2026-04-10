package com.microservices.orderservice.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microservices.orderservice.dto.OrderPlacedEvent;
import com.microservices.orderservice.model.OutboxEvent;
import com.microservices.orderservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Configuration
@EnableScheduling
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 5000) // Run every 5 seconds
    public void processOutboxEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findByProcessedFalseOrderByIdAsc();

        for (OutboxEvent event : pendingEvents) {
            try {
                OrderPlacedEvent payload = objectMapper.readValue(event.getPayload(), OrderPlacedEvent.class);
                kafkaTemplate.send("notificationTopic", payload).get(); // Synchronous send to ensure delivery
                event.setProcessed(true);
                outboxEventRepository.save(event);
                log.info("Successfully published outbox event [ID: {}] for Order: {}", event.getId(), payload.getOrderNumber());
            } catch (Exception e) {
                log.error("Failed to publish outbox event [ID: {}]. Will retry on next schedule.", event.getId(), e);
                // We don't break the loop, but in a real enterprise app you might implement dead-letter queues here.
            }
        }
    }
}
