package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.policy.CategoryPolicies;
import com.store.inventory.policy.CategoryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Comprueba que las políticas por categoría coinciden con las reglas del negocio, y que una
 * configuración incompleta o mal escrita falla enseguida (al arrancar) en lugar de descubrirse con un
 * cliente comprando.
 */
class CategoryPoliciesTest {

    /** Las políticas reales, cargadas de {@code category-policies.properties}. */
    private final CategoryPolicies policies = CategoryPolicies.defaults();

    /** Si alguien agrega una categoría al enum y olvida su configuración, este test lo avisa. */
    @Test
    void everyCategoryHasAPolicy() {
        for (ProductCategory category : ProductCategory.values()) {
            assertNotNull(policies.forCategory(category), "Missing policy for " + category);
        }
    }

    /**
     * Estos valores son los del enunciado (15 min, 24 h, 5 min con límite de 2). Si se cambian en el
     * archivo de configuración, este test avisa para que el cambio sea consciente.
     */
    @Test
    void defaultsMatchBusinessRules() {
        CategoryPolicy standard = policies.forCategory(ProductCategory.STANDARD);
        assertEquals(Duration.ofMinutes(15), standard.holdTime());
        assertEquals(OptionalInt.empty(), standard.maxUnitsPerOrder());

        CategoryPolicy preOrder = policies.forCategory(ProductCategory.PRE_ORDER);
        assertEquals(Duration.ofHours(24), preOrder.holdTime());
        assertEquals(OptionalInt.empty(), preOrder.maxUnitsPerOrder());

        CategoryPolicy flash = policies.forCategory(ProductCategory.FLASH_SALE);
        assertEquals(Duration.ofMinutes(5), flash.holdTime());
        assertEquals(OptionalInt.of(2), flash.maxUnitsPerOrder());
    }

    @Test
    void constructorFailsWhenACategoryIsMissing() {
        Map<ProductCategory, CategoryPolicy> incomplete =
                Map.of(ProductCategory.STANDARD, CategoryPolicy.of(Duration.ofMinutes(15)));
        assertThrows(IllegalStateException.class, () -> new CategoryPolicies(incomplete));
    }

    @Test
    void validateRejectsOverLimit() {
        CategoryPolicy flash = policies.forCategory(ProductCategory.FLASH_SALE);
        assertThrows(OrderLimitExceededException.class, () -> flash.validate("SKU-1", 3));
    }

    @Test
    void validateAcceptsAtLimit() {
        CategoryPolicy flash = policies.forCategory(ProductCategory.FLASH_SALE);
        assertDoesNotThrow(() -> flash.validate("SKU-1", 2));
    }

    @Test
    void unlimitedCategoriesAcceptLargeQuantities() {
        CategoryPolicy standard = policies.forCategory(ProductCategory.STANDARD);
        assertDoesNotThrow(() -> standard.validate("SKU-1", 10_000));
    }

    @Test
    void rejectsInvalidPolicy() {
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.of(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.of(Duration.ofMinutes(-1)));
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.of(Duration.ofMinutes(5), 0));
        assertThrows(IllegalArgumentException.class, () -> CategoryPolicy.of(Duration.ofMinutes(5), -1));
    }

    @Test
    void fromPropertiesReadsHoldTimeAndOptionalLimit() {
        Properties p = completeProperties();
        p.setProperty("STANDARD.hold-time", "PT30M");
        p.setProperty("STANDARD.max-units-per-order", "7");

        CategoryPolicy standard = CategoryPolicies.fromProperties(p).forCategory(ProductCategory.STANDARD);

        assertEquals(Duration.ofMinutes(30), standard.holdTime());
        assertEquals(OptionalInt.of(7), standard.maxUnitsPerOrder());
    }

    @Test
    void fromPropertiesFailsWhenAHoldTimeIsMissing() {
        Properties p = completeProperties();
        p.remove("PRE_ORDER.hold-time");
        assertThrows(IllegalStateException.class, () -> CategoryPolicies.fromProperties(p));
    }

    @Test
    void fromPropertiesFailsOnMalformedValues() {
        Properties badDuration = completeProperties();
        badDuration.setProperty("STANDARD.hold-time", "15 minutes");
        assertThrows(IllegalStateException.class, () -> CategoryPolicies.fromProperties(badDuration));

        Properties badLimit = completeProperties();
        badLimit.setProperty("FLASH_SALE.max-units-per-order", "two");
        assertThrows(IllegalStateException.class, () -> CategoryPolicies.fromProperties(badLimit));

        Properties zeroLimit = completeProperties();
        zeroLimit.setProperty("FLASH_SALE.max-units-per-order", "0");
        assertThrows(IllegalStateException.class, () -> CategoryPolicies.fromProperties(zeroLimit));
    }

    /** Propiedades válidas con un plazo por categoría; cada test estropea solo lo que prueba. */
    private static Properties completeProperties() {
        Properties p = new Properties();
        for (ProductCategory category : ProductCategory.values()) {
            p.setProperty(category.name() + ".hold-time", "PT10M");
        }
        return p;
    }

    @Test
    void expiresAtAddsHoldTime() {
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        CategoryPolicy standard = policies.forCategory(ProductCategory.STANDARD);
        assertEquals(Instant.parse("2026-01-01T10:15:00Z"), standard.expiresAt(now));
    }
}
