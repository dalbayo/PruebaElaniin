package com.store.inventory.web;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import io.swagger.v3.oas.annotations.media.Schema;
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
    record RegisterProductRequest(
            @Schema(description = "Identificador del producto", example = "STD-001") String sku,
            @Schema(description = "Categoría, que define el plazo de la reserva y el límite por pedido")
                    ProductCategory category) {
    }

    /**
     * Cuerpo de {@code POST /api/products/{sku}/stock}. La cantidad es {@code Integer} y no
     * {@code int} para poder distinguir "no vino" de 0.
     */
    record AddStockRequest(
            @Schema(description = "Unidades a agregar; debe ser mayor que 0", example = "10")
                    Integer quantity) {
    }

    /** Cuerpo de {@code POST /api/reservations}. La cantidad es {@code Integer} por el mismo motivo. */
    record ReserveRequest(
            @Schema(description = "Número del pedido; sirve para reconocer los reenvíos", example = "ORDER-1")
                    String orderId,
            @Schema(description = "Producto a reservar", example = "STD-001") String sku,
            @Schema(description = "Unidades a reservar; debe ser mayor que 0", example = "3")
                    Integer quantity) {
    }

    /** Respuesta de registrar un producto. */
    record ProductResponse(
            @Schema(example = "STD-001") String sku,
            ProductCategory category) {
    }

    /** Unidades que se pueden reservar de un producto. */
    record AvailabilityResponse(
            @Schema(example = "STD-001") String sku,
            @Schema(description = "Unidades que se pueden reservar ahora", example = "7") int available) {
    }

    /** Respuesta de reservar: los datos de la reserva, con su fecha de vencimiento. */
    record ReservationResponse(
            @Schema(example = "ORDER-1") String orderId,
            @Schema(example = "STD-001") String sku,
            @Schema(example = "3") int quantity,
            @Schema(description = "Momento en que la reserva vence si no se confirma",
                    example = "2026-01-01T10:15:00Z") Instant expiresAt) {

        static ReservationResponse from(Reservation reservation) {
            return new ReservationResponse(reservation.orderId(), reservation.sku(),
                    reservation.quantity(), reservation.expiresAt());
        }
    }

    /** Respuesta de confirmar un pedido. */
    record ConfirmationResponse(
            @Schema(example = "ORDER-1") String orderId,
            @Schema(example = "CONFIRMED") String status) {
    }

    /**
     * Cuerpo de todas las respuestas de error: el estado HTTP, un código estable pensado para que lo
     * lea un programa (por ejemplo {@code INSUFFICIENT_STOCK}) y un mensaje para una persona.
     */
    record ErrorResponse(
            @Schema(description = "Estado HTTP", example = "409") int status,
            @Schema(description = "Código estable: INVALID_REQUEST, INSUFFICIENT_STOCK, "
                    + "ORDER_LIMIT_EXCEEDED o INVALID_STATE", example = "INSUFFICIENT_STOCK") String code,
            @Schema(description = "Explicación para una persona") String message) {
    }
}
