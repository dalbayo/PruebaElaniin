package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Comprueba el plazo para pagar de cada categoría justo en el borde del vencimiento: un segundo antes
 * la reserva sigue vigente y en el instante exacto ya venció. Cada test se repite para las tres
 * categorías (15 min, 5 min y 24 h), con un reloj que se avanza a mano.
 */
class ReservationExpirationTest {

    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    private MutableClock clock;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        service = Inventory.create(clock, (sku, available) -> { });
    }

    /** Deja un producto de la categoría dada con 10 unidades, de las cuales 2 están reservadas. */
    private void reserveTwoUnitsOfTen(ProductCategory category) {
        service.registerProduct("SKU-1", category);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 2);
    }

    /** Las 2 unidades siguen apartadas hasta el último segundo y vuelven al stock al vencer. */
    @ParameterizedTest
    @CsvSource({"STANDARD,PT15M", "FLASH_SALE,PT5M", "PRE_ORDER,PT24H"})
    void unitsStayHeldUntilTheLastInstantAndComeBackAtExpiration(ProductCategory category, Duration window) {
        reserveTwoUnitsOfTen(category);

        clock.advance(window.minus(ONE_SECOND));
        assertEquals(8, service.available("SKU-1"));

        clock.advance(ONE_SECOND);
        assertEquals(10, service.available("SKU-1"));
    }

    /** Confirmar un segundo antes del vencimiento funciona; confirmar justo al vencer falla. */
    @ParameterizedTest
    @CsvSource({"STANDARD,PT15M", "FLASH_SALE,PT5M", "PRE_ORDER,PT24H"})
    void confirmWorksOneSecondBeforeExpirationAndFailsAtExpiration(ProductCategory category, Duration window) {
        reserveTwoUnitsOfTen(category);
        clock.advance(window.minus(ONE_SECOND));
        assertDoesNotThrow(() -> service.confirm("ORDER-1"));

        // Segundo pedido, en otro producto: se deja vencer para comprobar que ya no se puede confirmar.
        service.registerProduct("SKU-2", category);
        service.addStock("SKU-2", 10);
        service.reserve("ORDER-2", "SKU-2", 2);
        clock.advance(window);
        assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-2"));
        assertEquals(10, service.available("SKU-2"));
    }

    /** Una reserva ya pagada no vence, por mucho tiempo que pase: sus unidades quedan vendidas. */
    @ParameterizedTest
    @CsvSource({"STANDARD,PT15M", "FLASH_SALE,PT5M", "PRE_ORDER,PT24H"})
    void aConfirmedReservationNeverExpires(ProductCategory category, Duration window) {
        reserveTwoUnitsOfTen(category);
        service.confirm("ORDER-1");

        clock.advance(window.plus(Duration.ofDays(30)));

        assertEquals(8, service.available("SKU-1"));
    }
}
