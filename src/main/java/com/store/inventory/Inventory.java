package com.store.inventory;

import com.store.inventory.alert.CompositeStockAlertListener;
import com.store.inventory.alert.LowStockNotifier;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.policy.CategoryPolicies;
import com.store.inventory.repository.InMemoryInventoryRepository;
import com.store.inventory.service.DefaultInventoryService;
import java.time.Clock;
import java.util.Objects;

/**
 * Punto de entrada al servicio de inventario: es lo que usan la app y los tests automáticos del
 * equipo para obtener un {@link InventoryService} listo para usar.
 *
 * <p>La firma de {@link #create} no se debe cambiar, porque desde afuera se depende de ella. Lo que
 * sí puede cambiar es lo de adentro, y este es el único lugar donde se decide qué piezas concretas se
 * usan (el repositorio en memoria, las políticas y el notificador). Si el inventario pasa a una base
 * de datos, el cambio se hace aquí y no en las reglas de negocio.
 */
public final class Inventory {

    private Inventory() {
    }

    /**
     * Crea un servicio de inventario nuevo y vacío. Cada llamada devuelve un servicio independiente,
     * con sus propios datos: no se comparte nada entre dos llamadas.
     *
     * @param clock reloj con el que se calculan los vencimientos de las reservas; se recibe de afuera
     *        para poder probar el paso del tiempo sin esperar
     * @param alertListener canal al que llegan los avisos de bajo stock
     * @return el servicio listo para registrar productos y reservar unidades
     * @throws NullPointerException si {@code clock} o {@code alertListener} son nulos
     * @throws IllegalStateException si la configuración (plazos por categoría o umbral de aviso) es
     *         inválida; se falla aquí, al crear el servicio, y no cuando ya hay clientes comprando
     */
    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(alertListener, "alertListener");
        // El listener recibido se envuelve en un CompositeStockAlertListener para que, si falla, el
        // error no afecte a la reserva que originó el aviso.
        return new DefaultInventoryService(
                clock,
                new InMemoryInventoryRepository(),
                CategoryPolicies.defaults(),
                LowStockNotifier.withDefaults(CompositeStockAlertListener.of(alertListener)));
    }
}
