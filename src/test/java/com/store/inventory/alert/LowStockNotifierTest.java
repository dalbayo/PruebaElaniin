package com.store.inventory.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.ProductStock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comprueba cuándo se avisa de bajo stock (umbral de 5) y cuándo no: un solo aviso por cruce del
 * umbral, y un nuevo aviso solo después de que el producto se recupere, ya sea por reabastecimiento
 * o porque vencieron reservas. También prueba cómo se lee el umbral de la configuración.
 *
 * <p>Parten de un producto con 10 unidades y reservas de 15 minutos.
 */
class LowStockNotifierTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration HOLD = Duration.ofMinutes(15);

    /** Avisos entregados al canal de prueba. */
    private final List<LowStockAlert> received = new ArrayList<>();
    private LowStockNotifier notifier;
    private ProductStock stock;

    @BeforeEach
    void setUp() {
        notifier = new LowStockNotifier((sku, available) -> received.add(new LowStockAlert(sku, available)), 5);
        stock = new ProductStock("SKU-1", ProductCategory.STANDARD);
        stock.addUnits(10);
    }

    /** Repite lo que hace el servicio al reservar: re-armar, reservar, decidir el aviso y enviarlo. */
    private void reserve(String orderId, int quantity, Instant now) {
        notifier.rearmIfRestocked(stock, now);
        stock.reserve(orderId, quantity, now.plus(HOLD), now);
        notifier.evaluate(stock, now).ifPresent(notifier::send);
    }

    /** Repite lo que hace el servicio al agregar stock: solo re-arma, nunca avisa. */
    private void addStock(int quantity, Instant now) {
        stock.addUnits(quantity);
        notifier.rearmIfRestocked(stock, now);
    }

    @Test
    void noAlertAboveThreshold() {
        reserve("O1", 4, NOW);
        assertEquals(6, stock.availableAt(NOW));
        assertTrue(received.isEmpty());
    }

    @Test
    void alertsExactlyAtThreshold() {
        reserve("O1", 5, NOW);
        assertEquals(List.of(new LowStockAlert("SKU-1", 5)), received);
    }

    @Test
    void alertsOncePerCrossing() {
        reserve("O1", 5, NOW);
        reserve("O2", 1, NOW);
        reserve("O3", 1, NOW);
        assertEquals(List.of(new LowStockAlert("SKU-1", 5)), received);
    }

    @Test
    void singleBigReservationSendsOneAlertWithTheRemainingUnits() {
        reserve("O1", 10, NOW);
        assertEquals(List.of(new LowStockAlert("SKU-1", 0)), received);
    }

    @Test
    void restockAboveThresholdRearms() {
        reserve("O1", 6, NOW);
        addStock(10, NOW);
        reserve("O2", 10, NOW);
        assertEquals(List.of(new LowStockAlert("SKU-1", 4), new LowStockAlert("SKU-1", 4)), received);
    }

    @Test
    void restockThatStaysAtOrBelowThresholdDoesNotRearm() {
        reserve("O1", 6, NOW);
        // Quedan 4; al agregar 1 quedan 5, que sigue en el umbral (no lo supera): no se re-arma.
        addStock(1, NOW);
        assertEquals(5, stock.availableAt(NOW));
        reserve("O2", 1, NOW);
        assertEquals(List.of(new LowStockAlert("SKU-1", 4)), received);
    }

    @Test
    void expiryRearmsAlertOnTheNextOperation() {
        reserve("O1", 3, NOW);
        reserve("O2", 2, NOW);
        reserve("O3", 1, NOW);
        assertEquals(List.of(new LowStockAlert("SKU-1", 5)), received);
        received.clear();

        // Las tres reservas vencen. Nadie "avisa" de ese vencimiento: se nota en la siguiente
        // operación, que ve el producto otra vez por encima del umbral y vuelve a habilitar el aviso.
        Instant later = NOW.plus(HOLD).plusSeconds(1);
        assertEquals(10, stock.availableAt(later));
        reserve("O4", 7, later);

        assertEquals(List.of(new LowStockAlert("SKU-1", 3)), received);
    }

    @Test
    void evaluateMarksTheStockAsAlerted() {
        stock.reserve("O1", 8, NOW.plus(HOLD), NOW);
        assertFalse(stock.lowStockAlerted());
        assertTrue(notifier.evaluate(stock, NOW).isPresent());
        assertTrue(stock.lowStockAlerted());
        assertTrue(notifier.evaluate(stock, NOW).isEmpty());
    }

    @Test
    void sendNeverThrows() {
        LowStockNotifier failing = new LowStockNotifier((sku, available) -> {
            throw new IllegalStateException("smtp down");
        }, 5);
        failing.send(new LowStockAlert("SKU-1", 3));
    }

    @Test
    void customThreshold() {
        LowStockNotifier custom = new LowStockNotifier((sku, available) -> received.add(new LowStockAlert(sku, available)), 8);
        stock.reserve("O1", 2, NOW.plus(HOLD), NOW);
        custom.evaluate(stock, NOW).ifPresent(custom::send);
        assertEquals(List.of(new LowStockAlert("SKU-1", 8)), received);
    }

    @Test
    void thresholdIsReadFromProperties() {
        Properties properties = new Properties();
        properties.setProperty("low-stock-threshold", " 12 ");
        assertEquals(12, LowStockNotifier.fromProperties((s, a) -> { }, properties).threshold());
    }

    @Test
    void thresholdDefaultsWhenMissing() {
        assertEquals(5, LowStockNotifier.fromProperties((s, a) -> { }, new Properties()).threshold());
    }

    @Test
    void bundledSettingsFileDefinesFive() {
        assertEquals(5, LowStockNotifier.withDefaults((s, a) -> { }).threshold());
    }

    @Test
    void invalidThresholdFailsAtStartup() {
        Properties text = new Properties();
        text.setProperty("low-stock-threshold", "five");
        assertThrows(IllegalStateException.class, () -> LowStockNotifier.fromProperties((s, a) -> { }, text));

        Properties negative = new Properties();
        negative.setProperty("low-stock-threshold", "-1");
        assertThrows(IllegalStateException.class, () -> LowStockNotifier.fromProperties((s, a) -> { }, negative));
    }
}
