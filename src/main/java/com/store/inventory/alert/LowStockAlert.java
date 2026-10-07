package com.store.inventory.alert;

import java.util.Objects;

/**
 * Representa un aviso de "queda poco stock" para un producto.
 *
 * <p>Solo lleva lo que compras necesita saber: de qué producto se trata (su SKU) y cuántas
 * unidades quedan disponibles en el momento de decidir el aviso.
 *
 * <p>{@link LowStockNotifier} lo crea mientras el producto está bloqueado, pero se entrega
 * después de soltar ese bloqueo, para que un canal lento no detenga otras operaciones sobre el
 * mismo producto.
 */
public record LowStockAlert(String sku, int availableUnits) {

    public LowStockAlert {
        Objects.requireNonNull(sku, "sku");
    }
}
