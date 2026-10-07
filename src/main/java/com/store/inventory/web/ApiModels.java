package com.store.inventory.web;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Instant;

/**
 * Los cuerpos JSON que entran y salen de la API REST. Se agrupan aquí porque son solo contenedores de
 * datos, sin lógica. Son propios de la API: así lo que se publica por HTTP no queda atado a las clases
 * internas del contrato.
 */
final class ApiModels {

    private ApiModels() {
    }

    /** Cuerpo de {@code POST /api/products}. */
    record RegisterProductRequest(String sku, ProductCategory category) {
    }

    /**
     * Cuerpo de {@code POST /api/products/{sku}/stock}. La cantidad es {@code Integer} y no
     * {@code int} para poder distinguir "no vino" de 0.
     */
    record AddStockRequest(Integer quantity) {
    }

    /** Cuerpo de {@code POST /api/reservations}. La cantidad es {@code Integer} por el mismo motivo. */
    record ReserveRequest(String orderId, String sku, Integer quantity) {
    }

    /** Respuesta de registrar un producto. */
    record ProductResponse(String sku, ProductCategory category) {
    }

    /** Unidades que se pueden reservar de un producto. */
    record AvailabilityResponse(String sku, int available) {
    }

    /** Respuesta de reservar: los datos de la reserva, con su fecha de vencimiento. */
    record ReservationResponse(String orderId, String sku, int quantity, Instant expiresAt) {

        static ReservationResponse from(Reservation reservation) {
            return new ReservationResponse(reservation.orderId(), reservation.sku(),
                    reservation.quantity(), reservation.expiresAt());
        }
    }

    /** Respuesta de confirmar un pedido. */
    record ConfirmationResponse(String orderId, String status) {
    }

    /**
     * Cuerpo de todas las respuestas de error: el estado HTTP, un código estable pensado para que lo
     * lea un programa (por ejemplo {@code INSUFFICIENT_STOCK}) y un mensaje para una persona.
     */
    record ErrorResponse(int status, String code, String message) {
    }
}
