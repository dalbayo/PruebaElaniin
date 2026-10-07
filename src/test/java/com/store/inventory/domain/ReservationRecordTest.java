package com.store.inventory.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.Reservation;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Comprueba la reserva interna: cuándo está vigente y cuándo venció (con el borde exacto), que una
 * confirmada nunca vence, que es inmutable y que valida sus datos.
 */
class ReservationRecordTest {

    private static final Instant EXPIRES = Instant.parse("2026-01-01T10:15:00Z");
    private final ReservationRecord record = ReservationRecord.active("ORDER-1", "SKU-1", 3, EXPIRES);

    @Test
    void activeBeforeExpiry() {
        Instant now = EXPIRES.minusSeconds(1);
        assertTrue(record.isActiveAt(now));
        assertFalse(record.isExpiredAt(now));
    }

    @Test
    void expiredExactlyAtExpiry() {
        assertFalse(record.isActiveAt(EXPIRES));
        assertTrue(record.isExpiredAt(EXPIRES));
    }

    @Test
    void confirmedNeverExpires() {
        ReservationRecord confirmed = record.confirmed();
        Instant muchLater = EXPIRES.plusSeconds(86_400);
        assertTrue(confirmed.isConfirmed());
        assertFalse(confirmed.isExpiredAt(muchLater));
        assertFalse(confirmed.isActiveAt(muchLater));
    }

    @Test
    void confirmedReturnsACopyAndKeepsTheOriginal() {
        record.confirmed();
        assertFalse(record.isConfirmed());
    }

    @Test
    void rejectsInvalidQuantity() {
        assertThrows(IllegalArgumentException.class, () -> ReservationRecord.active("O", "S", 0, EXPIRES));
        assertThrows(IllegalArgumentException.class, () -> ReservationRecord.active("O", "S", -1, EXPIRES));
    }

    @Test
    void rejectsNullFields() {
        assertThrows(NullPointerException.class, () -> ReservationRecord.active(null, "S", 1, EXPIRES));
        assertThrows(NullPointerException.class, () -> ReservationRecord.active("O", null, 1, EXPIRES));
        assertThrows(NullPointerException.class, () -> ReservationRecord.active("O", "S", 1, null));
    }

    @Test
    void toApiCopiesAllFields() {
        assertEquals(new Reservation("ORDER-1", "SKU-1", 3, EXPIRES), record.toApi());
    }
}
