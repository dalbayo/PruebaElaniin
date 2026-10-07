package com.store.inventory.domain;

import com.store.inventory.api.Reservation;
import java.time.Instant;
import java.util.Objects;

/**
 * Representa una reserva dentro del sistema: qué pedido la hizo, de qué producto, cuántas unidades
 * aparta y hasta cuándo tiene el cliente para pagar.
 *
 * <p>Es inmutable: nunca se modifica. Cuando una reserva se confirma se crea una copia nueva con el
 * estado cambiado (ver {@link #confirmed()}), así se puede compartir sin miedo a que alguien la
 * cambie por debajo.
 *
 * <p>La app no ve esta clase: ve {@link Reservation}, que es lo que devuelve {@link #toApi()}. Esta es
 * la versión interna, que además guarda el estado.
 */
public record ReservationRecord(String orderId, String sku, int quantity, Instant expiresAt,
                                ReservationStatus status) {

    /** Valida que los datos tengan sentido: nada puede ser nulo y la cantidad debe ser al menos 1. */
    public ReservationRecord {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(status, "status");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    /** Crea una reserva nueva, en estado {@link ReservationStatus#ACTIVE}: esperando el pago. */
    public static ReservationRecord active(String orderId, String sku, int quantity, Instant expiresAt) {
        return new ReservationRecord(orderId, sku, quantity, expiresAt, ReservationStatus.ACTIVE);
    }

    /**
     * Dice si la reserva está esperando el pago y todavía tiene tiempo: su estado es
     * {@code ACTIVE} y {@code now} es anterior a {@code expiresAt}. Estas son las reservas que restan
     * de las unidades disponibles.
     *
     * <p>Justo en la hora de vencimiento ya no está activa: el límite es estricto. Una reserva
     * confirmada devuelve {@code false}, porque sus unidades ya salieron del stock.
     */
    public boolean isActiveAt(Instant now) {
        return status == ReservationStatus.ACTIVE && now.isBefore(expiresAt);
    }

    /**
     * Dice si la reserva venció sin pagarse: sigue en {@code ACTIVE} y {@code now} ya llegó o pasó
     * su hora de vencimiento. Una reserva confirmada nunca vence.
     */
    public boolean isExpiredAt(Instant now) {
        return status == ReservationStatus.ACTIVE && !now.isBefore(expiresAt);
    }

    public boolean isConfirmed() {
        return status == ReservationStatus.CONFIRMED;
    }

    /** Devuelve una copia de la reserva con estado {@code CONFIRMED}. La original no cambia. */
    public ReservationRecord confirmed() {
        return new ReservationRecord(orderId, sku, quantity, expiresAt, ReservationStatus.CONFIRMED);
    }

    /** Convierte la reserva al formato que ve la app ({@link Reservation}), sin el estado interno. */
    public Reservation toApi() {
        return new Reservation(orderId, sku, quantity, expiresAt);
    }
}
