package com.store.inventory.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.alert.LowStockAlert;
import com.store.inventory.alert.LowStockNotifier;
import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.policy.CategoryPolicies;
import com.store.inventory.repository.InMemoryInventoryRepository;
import com.store.inventory.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Prueba las reglas de negocio del servicio de inventario en un solo hilo: registro de productos,
 * stock, reservas, límites por categoría, vencimiento, reenvíos, confirmaciones y avisos de bajo
 * stock.
 *
 * <p>Usa un reloj que se adelanta a mano, así que los vencimientos se prueban sin esperar. Las pruebas
 * con varios hilos están en {@code ConcurrencyTest}. Los tests parten de un producto STANDARD
 * ({@code SKU-1}), cuyo plazo para pagar es de 15 minutos.
 */
class DefaultInventoryServiceTest {

    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration STANDARD_HOLD = Duration.ofMinutes(15);

    /** Avisos de bajo stock recibidos por el servicio del test. */
    private final List<LowStockAlert> alerts = new ArrayList<>();
    private MutableClock clock;
    private DefaultInventoryService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        service = serviceWith((sku, available) -> alerts.add(new LowStockAlert(sku, available)));
        service.registerProduct("SKU-1", ProductCategory.STANDARD);
    }

    /**
     * Crea un servicio con repositorio en memoria, las políticas reales y el umbral de aviso en 5,
     * que entrega los avisos al listener indicado.
     */
    private DefaultInventoryService serviceWith(com.store.inventory.api.StockAlertListener listener) {
        return new DefaultInventoryService(clock, new InMemoryInventoryRepository(),
                CategoryPolicies.defaults(), new LowStockNotifier(listener, 5));
    }

    // registerProduct

    @Test
    void registeringTwiceWithTheSameCategoryIsHarmless() {
        assertDoesNotThrow(() -> service.registerProduct("SKU-1", ProductCategory.STANDARD));
    }

    @Test
    void registeringWithAnotherCategoryFails() {
        assertThrows(IllegalArgumentException.class,
                () -> service.registerProduct("SKU-1", ProductCategory.FLASH_SALE));
    }

    @Test
    void blankOrNullSkuIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.registerProduct("  ", ProductCategory.STANDARD));
        assertThrows(NullPointerException.class, () -> service.registerProduct(null, ProductCategory.STANDARD));
        assertThrows(NullPointerException.class, () -> service.registerProduct("SKU-2", null));
    }

    // addStock

    @Test
    void addStockAccumulates() {
        service.addStock("SKU-1", 4);
        service.addStock("SKU-1", 6);
        assertEquals(10, service.available("SKU-1"));
    }

    @Test
    void addStockRejectsNonPositiveQuantity() {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", 0));
        assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", -3));
    }

    @Test
    void addStockOnUnregisteredProductFails() {
        assertThrows(IllegalArgumentException.class, () -> service.addStock("NOPE", 5));
    }

    // reserve

    @Test
    void reserveReducesAvailable() {
        service.addStock("SKU-1", 10);
        Reservation reservation = service.reserve("ORDER-1", "SKU-1", 3);
        assertEquals(7, service.available("SKU-1"));
        assertEquals(new Reservation("ORDER-1", "SKU-1", 3, START.plus(STANDARD_HOLD)), reservation);
    }

    @Test
    void reserveExactlyWhatIsAvailable() {
        service.addStock("SKU-1", 3);
        service.reserve("ORDER-1", "SKU-1", 3);
        assertEquals(0, service.available("SKU-1"));
    }

    @Test
    void reservingMoreThanAvailableFailsAndKeepsTheStock() {
        service.addStock("SKU-1", 2);
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "SKU-1", 3));
        assertEquals(2, service.available("SKU-1"));
    }

    @Test
    void reservingAnUnknownProductFailsAsInsufficientStock() {
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "NOPE", 1));
    }

    @Test
    void reserveRejectsNonPositiveQuantity() {
        service.addStock("SKU-1", 5);
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", 0));
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", -1));
    }

    @Test
    void expiryDependsOnTheCategory() {
        Map<ProductCategory, Duration> expected = Map.of(
                ProductCategory.STANDARD, Duration.ofMinutes(15),
                ProductCategory.PRE_ORDER, Duration.ofHours(24),
                ProductCategory.FLASH_SALE, Duration.ofMinutes(5));
        for (Map.Entry<ProductCategory, Duration> entry : expected.entrySet()) {
            String sku = "SKU-" + entry.getKey();
            service.registerProduct(sku, entry.getKey());
            service.addStock(sku, 10);
            Reservation reservation = service.reserve("ORDER-" + sku, sku, 1);
            assertEquals(START.plus(entry.getValue()), reservation.expiresAt(), entry.getKey().name());
        }
    }

    // límites por categoría

    @Test
    void flashSaleRejectsMoreThanTwoUnitsPerOrder() {
        service.registerProduct("FLASH", ProductCategory.FLASH_SALE);
        service.addStock("FLASH", 10);
        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-1", "FLASH", 3));
        assertEquals(10, service.available("FLASH"));
    }

    @Test
    void flashSaleAcceptsTwoUnits() {
        service.registerProduct("FLASH", ProductCategory.FLASH_SALE);
        service.addStock("FLASH", 10);
        assertDoesNotThrow(() -> service.reserve("ORDER-1", "FLASH", 2));
    }

    /** Con 1 unidad en stock y 3 pedidas, el error es el del límite, no el de falta de stock. */
    @Test
    void theLimitIsCheckedBeforeTheStock() {
        service.registerProduct("FLASH", ProductCategory.FLASH_SALE);
        service.addStock("FLASH", 1);
        assertThrows(OrderLimitExceededException.class, () -> service.reserve("ORDER-1", "FLASH", 3));
    }

    @Test
    void standardAndPreOrderHaveNoLimit() {
        service.registerProduct("PRE", ProductCategory.PRE_ORDER);
        service.addStock("SKU-1", 500);
        service.addStock("PRE", 500);
        assertDoesNotThrow(() -> service.reserve("ORDER-1", "SKU-1", 400));
        assertDoesNotThrow(() -> service.reserve("ORDER-2", "PRE", 400));
    }

    // expiración

    @Test
    void unitsComeBackExactlyWhenTheReservationExpires() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 4);

        clock.advance(STANDARD_HOLD.minusSeconds(1));
        assertEquals(6, service.available("SKU-1"));

        clock.advance(Duration.ofSeconds(1));
        assertEquals(10, service.available("SKU-1"));
    }

    @Test
    void anotherCustomerCanBuyTheUnitsOfAnExpiredReservation() {
        service.addStock("SKU-1", 3);
        service.reserve("ORDER-1", "SKU-1", 3);
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-2", "SKU-1", 1));

        clock.advance(STANDARD_HOLD);
        assertDoesNotThrow(() -> service.reserve("ORDER-2", "SKU-1", 3));
    }

    @Test
    void confirmedReservationNeverExpires() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 4);
        service.confirm("ORDER-1");
        clock.advance(Duration.ofDays(2));
        assertEquals(6, service.available("SKU-1"));
    }

    // reenvíos (idempotencia): la app reintenta el pedido cuando la conexión es lenta

    @Test
    void retryReturnsTheSameReservationWithoutDiscountingTwice() {
        service.addStock("SKU-1", 10);
        Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
        clock.advance(Duration.ofMinutes(2));
        Reservation retry = service.reserve("ORDER-1", "SKU-1", 3);

        assertEquals(first, retry);
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void retryWithAnotherQuantityFails() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 3);
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", 4));
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void theSameOrderCannotReserveAnotherProduct() {
        service.registerProduct("SKU-2", ProductCategory.STANDARD);
        service.addStock("SKU-1", 10);
        service.addStock("SKU-2", 10);
        service.reserve("ORDER-1", "SKU-1", 3);
        assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-2", 3));
        assertEquals(10, service.available("SKU-2"));
    }

    @Test
    void anOrderCanReserveAgainAfterItsReservationExpired() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 3);
        clock.advance(STANDARD_HOLD);
        Reservation again = service.reserve("ORDER-1", "SKU-1", 3);
        assertEquals(clock.instant().plus(STANDARD_HOLD), again.expiresAt());
        assertEquals(7, service.available("SKU-1"));
    }

    @Test
    void retryAfterConfirmationReturnsTheSameReservation() {
        service.addStock("SKU-1", 10);
        Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
        service.confirm("ORDER-1");
        assertEquals(first, service.reserve("ORDER-1", "SKU-1", 3));
        assertEquals(7, service.available("SKU-1"));
    }

    /**
     * ORDER-1 falla en SKU-1 (que no tiene stock). Ese intento fallido no debe dejar al pedido "ligado"
     * a SKU-1: si lo hiciera, la reserva posterior en SKU-2 se rechazaría.
     */
    @Test
    void aFailedReservationDoesNotPinTheOrderToTheProduct() {
        service.registerProduct("SKU-2", ProductCategory.STANDARD);
        service.addStock("SKU-2", 5);
        assertThrows(InsufficientStockException.class, () -> service.reserve("ORDER-1", "SKU-1", 1));
        assertDoesNotThrow(() -> service.reserve("ORDER-1", "SKU-2", 1));
    }

    // confirm

    @Test
    void confirmingKeepsTheAvailableUnits() {
        service.addStock("SKU-1", 5);
        service.reserve("ORDER-1", "SKU-1", 2);
        service.confirm("ORDER-1");
        assertEquals(3, service.available("SKU-1"));
    }

    @Test
    void confirmingTwiceDoesNotDiscountTwice() {
        service.addStock("SKU-1", 5);
        service.reserve("ORDER-1", "SKU-1", 2);
        service.confirm("ORDER-1");
        service.confirm("ORDER-1");
        assertEquals(3, service.available("SKU-1"));
    }

    @Test
    void confirmingAnUnknownOrderFails() {
        assertThrows(IllegalStateException.class, () -> service.confirm("NOPE"));
    }

    @Test
    void confirmingAnExpiredReservationFailsAndSellsNothing() {
        service.addStock("SKU-1", 5);
        service.reserve("ORDER-1", "SKU-1", 2);
        clock.advance(STANDARD_HOLD);
        assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
        assertEquals(5, service.available("SKU-1"));
    }

    // available

    @Test
    void availableOfAnUnknownProductIsZero() {
        assertEquals(0, service.available("NOPE"));
    }

    // avisos de bajo stock (umbral de 5)

    @Test
    void alertsWhenTheAvailableUnitsReachTheThreshold() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 4);
        assertTrue(alerts.isEmpty());
        service.reserve("ORDER-2", "SKU-1", 1);
        assertEquals(List.of(new LowStockAlert("SKU-1", 5)), alerts);
    }

    @Test
    void doesNotRepeatTheAlertWhileTheProductIsNotRestocked() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 5);
        service.reserve("ORDER-2", "SKU-1", 1);
        service.reserve("ORDER-3", "SKU-1", 1);
        assertEquals(1, alerts.size());
    }

    @Test
    void retryingTheReservationThatAlertedDoesNotAlertAgain() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 5);
        service.reserve("ORDER-1", "SKU-1", 5);
        assertEquals(1, alerts.size());
    }

    @Test
    void restockingAboveTheThresholdRearmsTheAlert() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 6);
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-2", "SKU-1", 10);
        assertEquals(List.of(new LowStockAlert("SKU-1", 4), new LowStockAlert("SKU-1", 4)), alerts);
    }

    @Test
    void expiringReservationsRearmsTheAlert() {
        service.addStock("SKU-1", 10);
        service.reserve("ORDER-1", "SKU-1", 5);
        clock.advance(STANDARD_HOLD);
        service.reserve("ORDER-2", "SKU-1", 6);
        assertEquals(List.of(new LowStockAlert("SKU-1", 5), new LowStockAlert("SKU-1", 4)), alerts);
    }

    @Test
    void addingStockNeverAlerts() {
        service.addStock("SKU-1", 3);
        assertTrue(alerts.isEmpty());
    }

    /** Si el canal de avisos falla (por ejemplo, el correo), la reserva igual se hace. */
    @Test
    void aFailingListenerDoesNotBreakTheReservation() {
        DefaultInventoryService failing = serviceWith((sku, available) -> {
            throw new IllegalStateException("smtp down");
        });
        failing.registerProduct("SKU-1", ProductCategory.STANDARD);
        failing.addStock("SKU-1", 10);
        assertDoesNotThrow(() -> failing.reserve("ORDER-1", "SKU-1", 8));
        assertEquals(2, failing.available("SKU-1"));
    }
}
