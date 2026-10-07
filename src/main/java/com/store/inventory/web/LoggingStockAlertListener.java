package com.store.inventory.web;

import com.store.inventory.api.StockAlertListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canal de avisos provisional: cuando queda poco stock, escribe el aviso en el log y nada más.
 *
 * <p>Existe para que la aplicación pueda arrancar sin un canal real. Un canal de verdad (correo,
 * SMS, chat) es otro {@link StockAlertListener}, que se agrega junto a este con
 * {@code CompositeStockAlertListener.of(...)}.
 */
public class LoggingStockAlertListener implements StockAlertListener {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingStockAlertListener.class);

    /** Escribe en el log, con nivel de advertencia, el producto y las unidades que quedan. */
    @Override
    public void onLowStock(String sku, int availableUnits) {
        LOG.warn("Low stock alert: sku={} availableUnits={}", sku, availableUnits);
    }
}
