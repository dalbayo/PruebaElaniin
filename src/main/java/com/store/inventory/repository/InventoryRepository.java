package com.store.inventory.repository;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.ProductStock;
import java.util.Optional;
import java.util.function.Function;

/**
 * Es el lugar donde se guarda el stock de los productos y a qué producto pertenece cada pedido.
 *
 * <p>El servicio solo habla con esta interfaz. Por eso hoy los datos pueden vivir en memoria y
 * mañana en una base de datos, sin tocar las reglas de negocio.
 *
 * <p>Lo más importante: el stock de un producto solo se puede usar dentro de {@link #withProduct},
 * que garantiza acceso exclusivo mientras dura la llamada (un bloqueo en memoria, o un bloqueo de
 * fila en una base de datos). Así nadie toca el stock de un producto sin tener ese acceso.
 */
public interface InventoryRepository {

    /**
     * Registra el producto si todavía no existe.
     *
     * @return la categoría con la que queda registrado el SKU: la nueva, o la que ya tenía si ya
     *         estaba registrado. Quien llama puede compararla con la que pidió para detectar un
     *         intento de registrarlo con otra categoría.
     */
    ProductCategory registerIfAbsent(String sku, ProductCategory category);

    /**
     * Ejecuta una acción con acceso exclusivo al stock de un producto. Mientras corre, nadie más
     * puede usar el stock de ese mismo producto (los demás productos no se bloquean).
     *
     * <p>Reglas para la acción:
     * <ul>
     *   <li>No puede devolver {@code null}.</li>
     *   <li>No debe llamar otra vez a {@code withProduct}: llamadas anidadas sobre productos
     *       distintos podrían bloquearse entre sí.</li>
     *   <li>No debe llamar a código externo lento, como los avisos de bajo stock, porque el
     *       producto queda bloqueado mientras la acción corre.</li>
     * </ul>
     *
     * @param sku producto sobre el que se quiere trabajar
     * @param action lo que se quiere hacer con el stock de ese producto
     * @param <T> tipo del resultado de la acción
     * @return el resultado de la acción, o vacío (sin ejecutarla) si el producto no está registrado
     */
    <T> Optional<T> withProduct(String sku, Function<ProductStock, T> action);

    /**
     * Busca a qué producto pertenece un pedido. Hace falta porque al confirmar un pedido solo se
     * recibe el número de pedido, no el producto.
     *
     * @return el SKU del pedido, o vacío si no se conoce ese pedido
     */
    Optional<String> findSkuByOrderId(String orderId);

    /**
     * Asocia un pedido a un producto, de forma atómica. Un pedido queda ligado a un solo producto: si
     * ya estaba asociado a otro, la asociación no cambia.
     *
     * @return el SKU al que el pedido ya estaba asociado, o vacío si acaba de quedar asociado a
     *         {@code sku}
     */
    Optional<String> bindOrderIfAbsent(String orderId, String sku);
}
