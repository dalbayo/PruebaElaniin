package com.store.inventory.web;

import com.store.inventory.Inventory;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Arma las piezas que necesita la API y crea el servicio de inventario usando su único punto de
 * entrada, {@link Inventory#create}.
 *
 * <p>Define tres elementos: el reloj, el canal de avisos de bajo stock y el servicio. El reloj y el
 * canal se definen aparte para poder reemplazarlos, por ejemplo con un reloj controlable en pruebas
 * o con un canal real de correo.
 */
@Configuration
public class InventoryConfig {

    /** Reloj real, en UTC. Es la hora con la que se calculan los vencimientos de las reservas. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Canal de avisos de bajo stock. Por ahora solo escribe el aviso en el log. */
    @Bean
    public StockAlertListener stockAlertListener() {
        return new LoggingStockAlertListener();
    }

    /** El servicio de inventario, creado con el reloj y el canal de avisos de arriba. */
    @Bean
    public InventoryService inventoryService(Clock clock, StockAlertListener stockAlertListener) {
        return Inventory.create(clock, stockAlertListener);
    }
}
