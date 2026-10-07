package com.store.inventory.web;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.web.ApiModels.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Convierte los errores del servicio de inventario en respuestas HTTP con un cuerpo uniforme:
 * {@code {"status": 409, "code": "INSUFFICIENT_STOCK", "message": "..."}}.
 *
 * <p>La correspondencia es esta:
 * <ul>
 *   <li>{@code IllegalArgumentException} y cuerpo ilegible: 400, código {@code INVALID_REQUEST}.</li>
 *   <li>{@code InsufficientStockException}: 409, código {@code INSUFFICIENT_STOCK}.</li>
 *   <li>{@code OrderLimitExceededException}: 422, código {@code ORDER_LIMIT_EXCEEDED}.</li>
 *   <li>{@code IllegalStateException}: 409, código {@code INVALID_STATE}.</li>
 * </ul>
 *
 * <p>El servicio usa la misma excepción para problemas distintos: {@link IllegalArgumentException}
 * para cantidad inválida, producto no registrado o conflictos de categoría o de pedido, e
 * {@link IllegalStateException} para "sin reserva activa" o "reserva vencida". Como esos tipos no se
 * pueden separar, cada uno se traduce a un único estado HTTP y el {@code message} dice cuál fue el
 * caso.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalidRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    /**
     * Cuerpo ausente o JSON mal formado (incluida una categoría que no existe). Se responde con un
     * mensaje fijo y simple, sin el detalle técnico del error de lectura.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request body is missing or malformed");
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> insufficientStock(InsufficientStockException e) {
        return body(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK", e.getMessage());
    }

    @ExceptionHandler(OrderLimitExceededException.class)
    public ResponseEntity<ErrorResponse> orderLimitExceeded(OrderLimitExceededException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, "ORDER_LIMIT_EXCEEDED", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> invalidState(IllegalStateException e) {
        return body(HttpStatus.CONFLICT, "INVALID_STATE", e.getMessage());
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(status.value(), code, message));
    }
}
