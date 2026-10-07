package com.store.inventory.repository;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.ProductStock;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Versión en memoria del repositorio: los datos se pierden al reiniciar la aplicación.
 *
 * <p>Usa un bloqueo por producto: dos clientes que compran productos distintos nunca se esperan;
 * solo se esperan entre sí los que compran el mismo producto. Se usa {@link ReentrantLock} y no
 * {@code synchronized} para no "clavar" los hilos virtuales de Java 21.
 *
 * <p>Ese bloqueo solo protege dentro de una misma aplicación en ejecución. Con varias instancias
 * del servicio hará falta otra implementación, apoyada en una base de datos.
 */
public final class InMemoryInventoryRepository implements InventoryRepository {

    /**
     * Un producto junto con su bloqueo. Cada producto tiene el suyo, y por eso los productos
     * distintos no se bloquean entre sí.
     */
    private static final class Entry {
        final ProductStock stock;
        final ReentrantLock lock = new ReentrantLock();

        Entry(ProductStock stock) {
            this.stock = stock;
        }
    }

    /** Productos registrados, por SKU. */
    private final ConcurrentMap<String, Entry> products = new ConcurrentHashMap<>();
    /** A qué producto pertenece cada pedido, por número de pedido. */
    private final ConcurrentMap<String, String> orderToSku = new ConcurrentHashMap<>();

    @Override
    public ProductCategory registerIfAbsent(String sku, ProductCategory category) {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(category, "category");
        // computeIfAbsent es atómico: si dos hilos registran el mismo SKU a la vez, se crea un solo
        // producto y los dos ven la misma categoría.
        return products.computeIfAbsent(sku, key -> new Entry(new ProductStock(key, category)))
                .stock.category();
    }

    /**
     * Busca el producto, toma su bloqueo, ejecuta la acción y suelta el bloqueo. Si el producto no
     * está registrado devuelve vacío y la acción no se ejecuta.
     */
    @Override
    public <T> Optional<T> withProduct(String sku, Function<ProductStock, T> action) {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(action, "action");
        Entry entry = products.get(sku);
        if (entry == null) {
            return Optional.empty();
        }
        entry.lock.lock();
        try {
            return Optional.of(action.apply(entry.stock));
        } finally {
            // El bloqueo se suelta siempre, incluso si la acción lanza una excepción. Si no, el
            // producto quedaría bloqueado para siempre.
            entry.lock.unlock();
        }
    }

    @Override
    public Optional<String> findSkuByOrderId(String orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return Optional.ofNullable(orderToSku.get(orderId));
    }

    @Override
    public Optional<String> bindOrderIfAbsent(String orderId, String sku) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(sku, "sku");
        // putIfAbsent es atómico y devuelve lo que ya había (si había algo): justo lo que se quiere
        // informar a quien llama.
        return Optional.ofNullable(orderToSku.putIfAbsent(orderId, sku));
    }
}
