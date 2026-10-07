package com.store.inventory.domain;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.ProductCategory;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Guarda el inventario de un producto (un SKU): cuántas unidades hay, cuántas se vendieron y qué
 * reservas tiene.
 *
 * <p>Es la pieza que impide vender más de lo que existe. Las unidades disponibles se calculan así:
 * unidades en bodega menos las unidades de las reservas que siguen esperando el pago (ver
 * {@link #availableAt}). Cuando una reserva se confirma, sus unidades salen de la bodega y pasan a
 * "vendidas", por lo que nunca vuelven al stock.
 *
 * <p><b>Esta clase no es segura para usar desde varios hilos por sí sola.</b> Quien la use debe tener
 * tomado el bloqueo del producto en todas las llamadas, incluidas las de lectura. De eso se encarga
 * el repositorio ({@code InventoryRepository#withProduct}).
 *
 * <p>También guarda la marca de "ya se avisó de bajo stock", que usa el paquete de alertas.
 */
public final class ProductStock {

    private final String sku;
    private final ProductCategory category;
    /**
     * Unidades en bodega que todavía no se vendieron. Incluye las que están reservadas esperando
     * pago: esas se descuentan al calcular la disponibilidad ({@link #availableAt}), no aquí.
     */
    private int onHand;
    private int sold;
    private boolean lowStockAlerted;
    /** Reservas de este producto, identificadas por el número de pedido. */
    private final Map<String, ReservationRecord> reservations = new HashMap<>();

    public ProductStock(String sku, ProductCategory category) {
        this.sku = Objects.requireNonNull(sku, "sku");
        this.category = Objects.requireNonNull(category, "category");
    }

    public String sku() {
        return sku;
    }

    public ProductCategory category() {
        return category;
    }

    /**
     * Total de unidades vendidas (confirmadas) hasta ahora. Es informativo: no influye en la
     * disponibilidad.
     */
    public int sold() {
        return sold;
    }

    /** Indica si ya se envió un aviso de bajo stock y el producto todavía no se ha recuperado. */
    public boolean lowStockAlerted() {
        return lowStockAlerted;
    }

    public void lowStockAlerted(boolean alerted) {
        this.lowStockAlerted = alerted;
    }

    /**
     * Agrega unidades a la bodega.
     *
     * @throws IllegalArgumentException si la cantidad no es positiva
     */
    public void addUnits(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        // addExact en lugar de una suma normal: si el total se pasara del máximo de un int, falla con
        // una excepción en vez de dar un número negativo sin avisar.
        onHand = Math.addExact(onHand, quantity);
    }

    /**
     * Calcula cuántas unidades se pueden reservar en el momento {@code now}: lo que hay en bodega
     * menos lo que tienen apartado las reservas que siguen esperando el pago.
     *
     * <p>Las reservas vencidas no restan, así que sus unidades cuentan como disponibles aunque nadie
     * las haya limpiado todavía. Este método solo lee: no modifica nada.
     */
    public int availableAt(Instant now) {
        int held = 0;
        for (ReservationRecord reservation : reservations.values()) {
            if (reservation.isActiveAt(now)) {
                held += reservation.quantity();
            }
        }
        return onHand - held;
    }

    public Optional<ReservationRecord> findReservation(String orderId) {
        return Optional.ofNullable(reservations.get(orderId));
    }

    /**
     * Aparta unidades para un pedido y devuelve la reserva creada.
     *
     * <p>Si el pedido ya tenía una reserva vencida sin confirmar, se reemplaza por la nueva: el
     * cliente está volviendo a intentarlo. Si tenía una reserva vigente o ya confirmada, lanza una
     * excepción: reconocer que es un reenvío del mismo pedido le toca a quien llama a este método.
     *
     * @param orderId número de pedido
     * @param quantity unidades que se quieren apartar
     * @param expiresAt hora en que vence la reserva si no se paga
     * @param now momento actual, para saber qué reservas siguen vigentes
     * @throws InsufficientStockException si hay menos unidades disponibles que las pedidas
     * @throws IllegalStateException si el pedido ya tiene una reserva vigente o confirmada
     */
    public ReservationRecord reserve(String orderId, int quantity, Instant expiresAt, Instant now) {
        ReservationRecord existing = reservations.get(orderId);
        // Una reserva vencida sin confirmar no bloquea al pedido: se reemplaza más abajo por la nueva.
        if (existing != null && !existing.isExpiredAt(now)) {
            throw new IllegalStateException("Order " + orderId + " already has a reservation");
        }
        int available = availableAt(now);
        if (quantity > available) {
            throw new InsufficientStockException(sku, quantity, available);
        }
        ReservationRecord record = ReservationRecord.active(orderId, sku, quantity, expiresAt);
        reservations.put(orderId, record);
        return record;
    }

    /**
     * Cancela una reserva que no se confirmó y devuelve sus unidades. Sirve para deshacer una
     * reserva que no se pudo completar.
     *
     * @throws IllegalStateException si el pedido no tiene una reserva sin confirmar (las unidades ya
     *         vendidas no se devuelven)
     */
    public void release(String orderId) {
        ReservationRecord reservation = reservations.get(orderId);
        if (reservation == null || reservation.isConfirmed()) {
            throw new IllegalStateException("Order " + orderId + " has no reservation to release");
        }
        reservations.remove(orderId);
    }

    /**
     * Convierte una reserva en venta: sus unidades salen de la bodega y se suman a las vendidas.
     *
     * <p>Si el pedido ya estaba confirmado, no hace nada y devuelve la misma reserva, así que
     * confirmar dos veces es seguro.
     *
     * @param orderId número de pedido a confirmar
     * @param now momento actual, para saber si la reserva todavía está vigente
     * @return la reserva ya confirmada
     * @throws IllegalStateException si el pedido no tiene reserva, o si esta ya venció
     */
    public ReservationRecord confirm(String orderId, Instant now) {
        ReservationRecord reservation = reservations.get(orderId);
        if (reservation == null) {
            throw new IllegalStateException("Order " + orderId + " has no active reservation");
        }
        if (reservation.isConfirmed()) {
            return reservation;
        }
        // Si ya venció, sus unidades pudieron apartarlas otros clientes: confirmarla ahora podría
        // vender más de lo que hay.
        if (reservation.isExpiredAt(now)) {
            throw new IllegalStateException("Reservation of order " + orderId + " has expired");
        }
        onHand -= reservation.quantity();
        sold += reservation.quantity();
        ReservationRecord confirmed = reservation.confirmed();
        reservations.put(orderId, confirmed);
        return confirmed;
    }

    /**
     * Borra las reservas vencidas para no acumular memoria. No cambia las unidades disponibles,
     * porque {@link #availableAt} ya ignora las vencidas. Las reservas confirmadas no se borran.
     */
    public void purgeExpired(Instant now) {
        reservations.values().removeIf(reservation -> reservation.isExpiredAt(now));
    }
}
