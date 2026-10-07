package com.store.inventory.domain;

/**
 * Estado guardado de una reserva: o está esperando el pago ({@link #ACTIVE}) o ya se pagó
 * ({@link #CONFIRMED}).
 *
 * <p>Que una reserva haya vencido <b>no</b> es un estado. Se deduce comparando su fecha de
 * vencimiento ({@link ReservationRecord#expiresAt()}) con la hora actual. Así nunca puede quedar
 * guardado un "vencida" desactualizado porque nadie se acordó de marcarlo.
 */
public enum ReservationStatus {
    /**
     * Las unidades están apartadas mientras el cliente paga. Si vence el plazo sin pagar, dejan de
     * contar.
     */
    ACTIVE,
    /** El pago se aprobó: las unidades están vendidas y la reserva ya no vence. */
    CONFIRMED
}
