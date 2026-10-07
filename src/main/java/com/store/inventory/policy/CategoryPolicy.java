package com.store.inventory.policy;

import com.store.inventory.api.OrderLimitExceededException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Reglas de negocio de una categoría de productos: cuánto tiempo tiene el cliente para pagar y
 * cuántas unidades puede reservar un solo pedido.
 *
 * <p>Por ejemplo, una categoría puede dar 5 minutos para pagar y limitar cada pedido a 2 unidades.
 * Si la categoría no tiene límite de unidades, {@code maxUnitsPerOrder} viene vacío.
 *
 * <p>Los valores concretos de cada categoría no están aquí, sino en el archivo de configuración
 * {@code category-policies.properties} (ver {@link CategoryPolicies}).
 */
public record CategoryPolicy(Duration holdTime, OptionalInt maxUnitsPerOrder) {

    /**
     * Valida que la política tenga sentido: el plazo para pagar y el límite (si lo hay) deben ser
     * positivos. Es mejor fallar al crearla que descubrir un valor absurdo cuando ya hay clientes
     * comprando.
     */
    public CategoryPolicy {
        Objects.requireNonNull(holdTime, "holdTime");
        Objects.requireNonNull(maxUnitsPerOrder, "maxUnitsPerOrder");
        if (holdTime.isZero() || holdTime.isNegative()) {
            throw new IllegalArgumentException("holdTime must be positive");
        }
        if (maxUnitsPerOrder.isPresent() && maxUnitsPerOrder.getAsInt() <= 0) {
            throw new IllegalArgumentException("maxUnitsPerOrder must be positive");
        }
    }

    /** Crea una política sin límite de unidades por pedido. */
    public static CategoryPolicy of(Duration holdTime) {
        return new CategoryPolicy(holdTime, OptionalInt.empty());
    }

    /** Crea una política con un límite de unidades por pedido. */
    public static CategoryPolicy of(Duration holdTime, int maxUnitsPerOrder) {
        return new CategoryPolicy(holdTime, OptionalInt.of(maxUnitsPerOrder));
    }

    /**
     * Comprueba que un pedido no pida más unidades de las que permite la categoría. Si la categoría
     * no tiene límite, no hace nada.
     *
     * @param sku producto del pedido (solo se usa para armar el mensaje del error)
     * @param quantity unidades que pide el pedido
     * @throws OrderLimitExceededException si la cantidad supera el límite por pedido
     */
    public void validate(String sku, int quantity) {
        if (maxUnitsPerOrder.isPresent() && quantity > maxUnitsPerOrder.getAsInt()) {
            throw new OrderLimitExceededException(sku, quantity, maxUnitsPerOrder.getAsInt());
        }
    }

    /**
     * Calcula hasta cuándo vale una reserva: la hora en que se hizo más el plazo para pagar.
     *
     * @param reservedAt momento en que se hizo la reserva
     * @return hora en que la reserva vence si no se paga
     */
    public Instant expiresAt(Instant reservedAt) {
        return reservedAt.plus(holdTime);
    }
}
