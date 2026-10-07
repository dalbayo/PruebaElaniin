package com.store.inventory.alert;

import com.store.inventory.api.StockAlertListener;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reparte cada aviso de bajo stock a todos los canales configurados (por ejemplo correo, SMS o chat).
 *
 * <p>El resto del sistema solo conoce un único {@link StockAlertListener}. Esta clase hace que ese
 * único listener, en realidad, avise a varios. Si mañana se quiere agregar un canal nuevo, basta con
 * sumarlo a la lista: ni el servicio ni el notificador tienen que cambiar.
 *
 * <p>Si un canal falla, el error se registra en el log y se sigue con los demás. Un problema con el
 * correo no debe impedir el aviso por otro canal, ni afectar a la reserva que originó la alerta.
 */
public final class CompositeStockAlertListener implements StockAlertListener {

    private static final Logger LOG = Logger.getLogger(CompositeStockAlertListener.class.getName());

    private final List<StockAlertListener> delegates;

    /**
     * @param delegates los canales que recibirán cada aviso, en el orden de la lista
     */
    public CompositeStockAlertListener(List<StockAlertListener> delegates) {
        Objects.requireNonNull(delegates, "delegates");
        // Se guarda una copia: si alguien modifica después la lista original, no cambia a quién
        // avisamos. La copia además rechaza canales nulos.
        this.delegates = List.copyOf(delegates);
    }

    /**
     * Atajo para crear el conjunto de canales a partir de varios listeners, por ejemplo
     * {@code CompositeStockAlertListener.of(correo, sms)}.
     */
    public static CompositeStockAlertListener of(StockAlertListener... delegates) {
        return new CompositeStockAlertListener(List.of(delegates));
    }

    /**
     * Avisa a cada canal, uno por uno y en orden.
     *
     * <p>Si un canal lanza una excepción, se registra en el log (con el SKU y el nombre del canal) y se
     * continúa con el siguiente. La excepción no sale de este método.
     */
    @Override
    public void onLowStock(String sku, int availableUnits) {
        for (StockAlertListener delegate : delegates) {
            try {
                delegate.onLowStock(sku, availableUnits);
            } catch (RuntimeException e) {
                // Un canal roto no debe afectar a los demás. Solo se atrapan RuntimeException: los
                // Error graves (por ejemplo, falta de memoria) sí deben seguir su camino.
                LOG.log(Level.WARNING,
                        "Alert listener " + delegate.getClass().getName() + " failed for " + sku, e);
            }
        }
    }
}
