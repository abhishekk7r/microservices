package com.microservices.InventoryService.service;

import com.microservices.InventoryService.dto.InventoryRequest;
import com.microservices.InventoryService.dto.InventoryResponse;
import com.microservices.InventoryService.model.Inventory;
import com.microservices.InventoryService.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    @Transactional(readOnly = true)
    public List<InventoryResponse> getStock(List<String> skuCode) {
        return inventoryRepository.findBySkuCodeIn(skuCode).stream()
                .map(inventory ->
                        InventoryResponse.builder()
                                .skuCode(inventory.getSkuCode())
                                .quantity(inventory.getQuantity())
                                .build()
                ).toList();
    }

    @Transactional
    public boolean checkAndReduceStock(List<InventoryRequest> requests) {
        // Extract and sort SKU codes to prevent deadlocks when acquiring pessimistic locks
        List<String> sortedSkus = requests.stream()
                .map(InventoryRequest::getSkuCode)
                .sorted()
                .toList();

        // Fetch and lock all requested inventory items in a single query
        List<Inventory> inventories = inventoryRepository.findBySkuCodeIn(sortedSkus);

        // Verify all products exist
        if (inventories.size() != requests.size()) {
            throw new IllegalArgumentException("One or more products not found in inventory");
        }

        // Map for quick lookup
        java.util.Map<String, Inventory> inventoryMap = inventories.stream()
                .collect(java.util.stream.Collectors.toMap(Inventory::getSkuCode, i -> i));

        // Check if there is enough stock for all requests
        for (InventoryRequest request : requests) {
            Inventory inventory = inventoryMap.get(request.getSkuCode());
            if (inventory.getQuantity() < request.getQuantity()) {
                return false; // Not enough stock
            }
        }

        // All checks passed, reduce quantities
        for (InventoryRequest request : requests) {
            Inventory inventory = inventoryMap.get(request.getSkuCode());
            inventory.setQuantity(inventory.getQuantity() - request.getQuantity());
        }

        // Save all changes in bulk
        inventoryRepository.saveAll(inventories);

        return true;
    }
}
