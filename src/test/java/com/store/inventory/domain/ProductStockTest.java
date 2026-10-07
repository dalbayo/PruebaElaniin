package com.store.inventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.ProductCategory;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Prueba las reglas del stock de un producto sin servicio ni hilos de por medio: la hora se le pasa a
 * mano a cada método. Cubre reservar, vencer, confirmar, vender, deshacer y limpiar reservas.
 *
 * <p>Todos los tests parten de un producto con 10 unidades y reservas de 15 minutos.
 */
class ProductStockTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration HOLD = Duration.ofMinutes(15);

    private ProductStock stock;

    @BeforeEach
    void setUp() {
        stock = new ProductStock("SKU-1", ProductCategory.STANDARD);
        stock.addUnits(10);
    }

    @Test
    void reserveReducesAvailable() {
        stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW);
        assertEquals(7, stock.availableAt(NOW));
    }

    @Test
    void cannotReserveMoreThanAvailable() {
        assertThrows(InsufficientStockException.class, () -> stock.reserve("ORDER-1", 11, NOW.plus(HOLD), NOW));
        assertEquals(10, stock.availableAt(NOW));
    }

    @Test
    void canReserveExactlyWhatIsAvailable() {
        stock.reserve("ORDER-1", 10, NOW.plus(HOLD), NOW);
        assertEquals(0, stock.availableAt(NOW));
    }

    @Test
    void expiredReservationReleasesUnits() {
        stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW);
        assertEquals(10, stock.availableAt(NOW.plus(HOLD)));
    }

    @Test
    void confirmKeepsAvailableAndSellsTheUnits() {
        stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW);
        stock.confirm("ORDER-1", NOW.plusSeconds(60));
        assertEquals(7, stock.availableAt(NOW.plusSeconds(60)));
        assertEquals(7, stock.availableAt(NOW.plus(HOLD).plusSeconds(3600)));
    }

    @Test
    void confirmTwiceIsIdempotent() {
        stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW);
        stock.confirm("ORDER-1", NOW);
        stock.confirm("ORDER-1", NOW);
        assertEquals(7, stock.availableAt(NOW));
        assertEquals(3, stock.sold());
    }

    @Test
    void soldCountsOnlyConfirmedUnits() {
        assertEquals(0, stock.sold());
        stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW);
        stock.reserve("ORDER-2", 2, NOW.plus(HOLD), NOW);
        assertEquals(0, stock.sold());
        stock.confirm("ORDER-1", NOW);
        assertEquals(3, stock.sold());
    }

    @Test
    void expiredReservationIsNeverCountedAsSold() {
        stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW);
        assertThrows(IllegalStateException.class, () -> stock.confirm("ORDER-1", NOW.plus(HOLD)));
        assertEquals(0, stock.sold());
    }

    @Test
    void confirmExpiredFails() {
        stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW);
        assertThrows(IllegalStateException.class, () -> stock.confirm("ORDER-1", NOW.plus(HOLD)));
        assertEquals(10, stock.availableAt(NOW.plus(HOLD)));
    }

    @Test
    void confirmUnknownFails() {
        assertThrows(IllegalStateException.class, () -> stock.confirm("NOPE", NOW));
    }

    @Test
    void duplicateOrderIdIsRejectedWhileActive() {
        stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW);
        assertThrows(IllegalStateException.class, () -> stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW));
        assertEquals(7, stock.availableAt(NOW));
    }

    @Test
    void duplicateOrderIdIsRejectedWhenConfirmed() {
        stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW);
        stock.confirm("ORDER-1", NOW);
        assertThrows(IllegalStateException.class,
                () -> stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW.plusSeconds(1)));
    }

    /** Si el cliente reintenta un pedido cuya reserva venció, la nueva reemplaza a la vieja. */
    @Test
    void expiredOrderIdCanReserveAgain() {
        stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW);
        Instant later = NOW.plus(HOLD).plusSeconds(1);
        stock.reserve("ORDER-1", 4, later.plus(HOLD), later);
        assertEquals(6, stock.availableAt(later));
    }

    @Test
    void purgeDoesNotChangeAvailability() {
        stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW);
        stock.reserve("ORDER-2", 2, NOW.plus(Duration.ofHours(1)), NOW);
        Instant later = NOW.plus(HOLD).plusSeconds(1);
        int before = stock.availableAt(later);
        stock.purgeExpired(later);
        assertEquals(before, stock.availableAt(later));
        assertTrue(stock.findReservation("ORDER-1").isEmpty());
        assertTrue(stock.findReservation("ORDER-2").isPresent());
    }

    @Test
    void purgeKeepsConfirmedReservations() {
        stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW);
        stock.confirm("ORDER-1", NOW);
        stock.purgeExpired(NOW.plus(Duration.ofDays(1)));
        assertTrue(stock.findReservation("ORDER-1").isPresent());
    }

    @Test
    void releaseReturnsTheUnits() {
        stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW);
        stock.release("ORDER-1");
        assertEquals(10, stock.availableAt(NOW));
        assertTrue(stock.findReservation("ORDER-1").isEmpty());
    }

    @Test
    void releaseFailsForUnknownOrConfirmedOrders() {
        assertThrows(IllegalStateException.class, () -> stock.release("NOPE"));
        stock.reserve("ORDER-1", 3, NOW.plus(HOLD), NOW);
        stock.confirm("ORDER-1", NOW);
        assertThrows(IllegalStateException.class, () -> stock.release("ORDER-1"));
        assertEquals(3, stock.sold());
    }

    @Test
    void addUnitsRejectsNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class, () -> stock.addUnits(0));
        assertThrows(IllegalArgumentException.class, () -> stock.addUnits(-5));
    }
}
