package com.store.inventory.alert;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.StockAlertListener;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Comprueba que el conjunto de canales avisa a todos en orden, que un canal que falla no impide el
 * aviso a los demás, y que la lista de canales se protege de cambios externos.
 */
class CompositeStockAlertListenerTest {

    /** Aquí queda anotado cada aviso recibido, con el nombre del canal que lo recibió. */
    private final List<String> calls = new ArrayList<>();

    /** Canal de prueba que solo anota los avisos que recibe. */
    private StockAlertListener recording(String name) {
        return (sku, available) -> calls.add(name + ":" + sku + ":" + available);
    }

    /** Canal de prueba que siempre falla, como un servidor de correo caído. */
    private static StockAlertListener failing() {
        return (sku, available) -> {
            throw new IllegalStateException("channel down");
        };
    }

    @Test
    void callsEveryListenerInOrder() {
        CompositeStockAlertListener composite = CompositeStockAlertListener.of(recording("email"), recording("sms"));
        composite.onLowStock("SKU-1", 4);
        assertEquals(List.of("email:SKU-1:4", "sms:SKU-1:4"), calls);
    }

    @Test
    void failingListenerDoesNotBlockTheOthers() {
        CompositeStockAlertListener composite =
                CompositeStockAlertListener.of(failing(), recording("sms"), failing(), recording("slack"));
        assertDoesNotThrow(() -> composite.onLowStock("SKU-1", 3));
        assertEquals(List.of("sms:SKU-1:3", "slack:SKU-1:3"), calls);
    }

    @Test
    void emptyListIsAllowed() {
        assertDoesNotThrow(() -> new CompositeStockAlertListener(List.of()).onLowStock("SKU-1", 1));
    }

    @Test
    void copiesTheListDefensively() {
        List<StockAlertListener> source = new ArrayList<>(List.of(recording("email")));
        CompositeStockAlertListener composite = new CompositeStockAlertListener(source);
        source.add(recording("sms"));
        composite.onLowStock("SKU-1", 2);
        assertEquals(List.of("email:SKU-1:2"), calls);
    }

    @Test
    void rejectsNullDelegates() {
        assertThrows(NullPointerException.class, () -> new CompositeStockAlertListener(null));
        List<StockAlertListener> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new CompositeStockAlertListener(withNull));
    }
}
