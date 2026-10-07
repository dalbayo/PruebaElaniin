package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Prueba el punto de entrada {@code Inventory.create}: que exija sus argumentos y que los avisos de
 * bajo stock lleguen al listener recibido, incluso si ese listener falla.
 */
class InventoryTest {

    @Test
    void createRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> Inventory.create(null, (sku, available) -> { }));
        assertThrows(NullPointerException.class, () -> Inventory.create(Clock.systemUTC(), null));
    }

    @Test
    void alertsReachTheListenerPassedToCreate() {
        List<String> alerts = new ArrayList<>();
        InventoryService service = Inventory.create(Clock.systemUTC(),
                (sku, available) -> alerts.add(sku + ":" + available));
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 6);
        assertEquals(List.of("SKU-1:4"), alerts);
    }

    /** Un canal de avisos caído (por ejemplo, el correo) no debe impedir que la reserva se haga. */
    @Test
    void aListenerThatThrowsDoesNotBreakTheService() {
        InventoryService service = Inventory.create(Clock.systemUTC(), (sku, available) -> {
            throw new IllegalStateException("smtp down");
        });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 6);
        assertEquals(4, service.available("SKU-1"));
    }
}
