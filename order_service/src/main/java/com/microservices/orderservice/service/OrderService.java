package com.microservices.orderservice.service;

import com.microservices.orderservice.dto.*;
import com.microservices.orderservice.model.Order;
import com.microservices.orderservice.model.OrderLineItems;
import com.microservices.orderservice.model.OutboxEvent;
import com.microservices.orderservice.repository.OrderRepository;
import com.microservices.orderservice.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker(name = "inventory", fallbackMethod = "fallbackMethod")
    @io.github.resilience4j.timelimiter.annotation.TimeLimiter(name = "inventory")
    @io.github.resilience4j.retry.annotation.Retry(name = "inventory")
    public java.util.concurrent.CompletableFuture<String> placeOrder(OrderRequest orderRequest) {
        // Create order with random ID
        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());

        // Map DTOs to entities
        List<OrderLineItems> orderLineItems = orderRequest.getOrderLineItemsDtoList()
                .stream()
                .map(this::mapToEntity)
                .toList();
        order.setOrderLineItemsList(orderLineItems);

        List<InventoryRequest> requests = order.getOrderLineItemsList().stream()
                .map(item -> new InventoryRequest(item.getSkuCode(), item.getQuantity()))
                .toList();

        // 1. Make network call outside of the database transaction
        boolean stockAvailable = Boolean.TRUE.equals(webClientBuilder.build().post()
                .uri("http://inventory-service/api/inventory/check")
                .bodyValue(requests)
                .retrieve()
                .bodyToMono(Boolean.class)
                .block());

        if (stockAvailable) {
            // 2. Wrap database saves inside an explicit transaction template
            transactionTemplate.executeWithoutResult(status -> {
                log.info("Saving Order to Database: {}", order.getOrderNumber());
                orderRepository.save(order);

                try {
                    OrderPlacedEvent eventDto = new OrderPlacedEvent(order.getOrderNumber());
                    OutboxEvent outboxEvent = com.microservices.orderservice.model.OutboxEvent.builder()
                            .aggregateId(order.getOrderNumber())
                            .type("OrderPlacedEvent")
                            .payload(objectMapper.writeValueAsString(eventDto))
                            .createdAt(java.time.LocalDateTime.now())
                            .processed(false)
                            .build();
                    outboxEventRepository.save(outboxEvent);
                    log.info("Saved outbox event for processing: {}", order.getOrderNumber());
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    status.setRollbackOnly();
                    throw new RuntimeException("Failed to serialize outbox event", e);
                }
            });
            return java.util.concurrent.CompletableFuture.completedFuture("Order Placed Successfully");
        } else {
            throw new IllegalArgumentException("One or more products are out of stock, please try again later");
        }
    }

    public java.util.concurrent.CompletableFuture<String> fallbackMethod(OrderRequest orderRequest,
            RuntimeException runtimeException) {
        log.error("Fallback path activated for order. Error: {}", runtimeException.getMessage());
        return java.util.concurrent.CompletableFuture
                .completedFuture("Oops! Something went wrong, please order after some time!");
    }

    private OrderLineItems mapToEntity(OrderLineItemsDto dto) {
        OrderLineItems entity = new OrderLineItems();
        entity.setPrice(dto.getPrice());
        entity.setQuantity(dto.getQuantity());
        entity.setSkuCode(dto.getSkuCode());
        return entity;
    }
}
