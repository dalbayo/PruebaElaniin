package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pruebas básicas del flujo principal que vienen con el ejercicio: reservar descuenta unidades, no se
 * puede reservar más de lo que hay, y lo confirmado queda vendido.
 *
 * <p>Usan el punto de entrada real, {@code Inventory.create}, con el reloj del sistema y un canal de
 * avisos que ignora todo. Son las pruebas que deben seguir pasando sin cambios.
 */
class InventoryServiceTest {

    private InventoryService service;

    /** Cada test parte de un producto STANDARD ya registrado y todavía sin stock. */
    @BeforeEach
    void setUp() {
        service = Inventory.create(Clock.systemUTC(), (sku, available) -> { });
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
    }

    /** De 10 unidades, reservar 3 deja 7 disponibles. */
    @Test
    void reservingReducesAvailableUnits() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 3);
        assertEquals(7, service.available("SKU-1"));
    }

    /** Con 2 unidades, pedir 3 falla con "stock insuficiente". */
    @Test
    void cannotReserveMoreThanAvailable() {
        service.addStock("SKU-1", 2);
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "SKU-1", 3));
    }

    /** Confirmar no devuelve las unidades al stock: de 5, reservar 2 y confirmar deja 3 disponibles. */
    @Test
    void confirmedUnitsStaySold() {
        service.addStock("SKU-1", 5);
        service.reserve("ORDER-1", "SKU-1", 2);
        service.confirm("ORDER-1");
        assertEquals(3, service.available("SKU-1"));
    }
}
