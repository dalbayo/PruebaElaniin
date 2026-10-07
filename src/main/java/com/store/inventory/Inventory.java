package com.store.inventory;

import com.store.inventory.alert.CompositeStockAlertListener;
import com.store.inventory.alert.LowStockNotifier;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.policy.CategoryPolicies;
import com.store.inventory.repository.InMemoryInventoryRepository;
import com.store.inventory.service.DefaultInventoryService;
import java.time.Clock;
import java.util.Objects;

/**
 * Entry point used by our automated tests. Keep this signature exactly as it is,
 * and build your implementation here.
 */
public final class Inventory {

    private Inventory() {
    }

    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(alertListener, "alertListener");
        return new DefaultInventoryService(
                clock,
                new InMemoryInventoryRepository(),
                CategoryPolicies.defaults(),
                LowStockNotifier.withDefaults(CompositeStockAlertListener.of(alertListener)));
    }
}
