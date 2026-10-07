package com.store.inventory.policy;

import com.store.inventory.api.ProductCategory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * Guarda la política (plazo para pagar y límite por pedido) de cada {@link ProductCategory}.
 *
 * <p>Es el lugar al que el resto del sistema le pregunta "¿qué reglas tiene esta categoría?". Así la
 * lógica de reservas no necesita saber qué categorías existen ni cuánto vale cada plazo.
 *
 * <p>Los valores se leen del archivo {@code category-policies.properties}. Para agregar una
 * categoría nueva se agrega al enum y se escriben sus claves en ese archivo. Si a alguna categoría
 * le falta su política, la creación falla al arrancar, y no cuando ya hay clientes comprando.
 */
public final class CategoryPolicies {

    static final String DEFAULT_RESOURCE = "category-policies.properties";
    private static final String HOLD_TIME_KEY = "hold-time";
    private static final String MAX_UNITS_KEY = "max-units-per-order";

    private final Map<ProductCategory, CategoryPolicy> policies;

    /**
     * @param policies política de cada categoría; debe incluir todas las del enum
     * @throws IllegalStateException si alguna categoría no tiene política
     */
    public CategoryPolicies(Map<ProductCategory, CategoryPolicy> policies) {
        // Se trabaja sobre una copia para que, si alguien modifica después el mapa que entregó, estas
        // reglas no cambien por debajo.
        EnumMap<ProductCategory, CategoryPolicy> copy = new EnumMap<>(ProductCategory.class);
        copy.putAll(policies);
        for (ProductCategory category : ProductCategory.values()) {
            if (copy.get(category) == null) {
                throw new IllegalStateException("No policy defined for category " + category);
            }
        }
        this.policies = Collections.unmodifiableMap(copy);
    }

    /**
     * Devuelve la política de una categoría. Nunca es nula, porque el constructor exige que estén
     * todas las categorías.
     */
    public CategoryPolicy forCategory(ProductCategory category) {
        return policies.get(category);
    }

    /**
     * Carga las políticas del archivo {@code category-policies.properties}, que viene empaquetado
     * dentro de la aplicación.
     *
     * @throws IllegalStateException si el archivo no está o su contenido no es válido
     */
    public static CategoryPolicies defaults() {
        try (InputStream in = CategoryPolicies.class.getClassLoader().getResourceAsStream(DEFAULT_RESOURCE)) {
            // Sin este archivo no hay reglas para ninguna categoría: se falla de una vez.
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + DEFAULT_RESOURCE);
            }
            Properties properties = new Properties();
            properties.load(in);
            return fromProperties(properties);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + DEFAULT_RESOURCE, e);
        }
    }

    /**
     * Arma las políticas a partir de propiedades ya cargadas, con claves como:
     * <ul>
     *   <li>{@code FLASH_SALE.hold-time=PT5M}: plazo para pagar, en formato de duración ISO-8601.
     *       Es obligatorio.</li>
     *   <li>{@code FLASH_SALE.max-units-per-order=2}: límite de unidades por pedido. Es opcional: si
     *       falta o está vacío, la categoría no tiene límite.</li>
     * </ul>
     *
     * <p>Recorre todas las categorías del enum. Las claves que no correspondan a ninguna categoría
     * se ignoran.
     *
     * @throws IllegalStateException si a una categoría le falta {@code hold-time} o algún valor está
     *         mal escrito
     */
    public static CategoryPolicies fromProperties(Properties properties) {
        Map<ProductCategory, CategoryPolicy> parsed = new EnumMap<>(ProductCategory.class);
        for (ProductCategory category : ProductCategory.values()) {
            String holdKey = category.name() + "." + HOLD_TIME_KEY;
            String maxKey = category.name() + "." + MAX_UNITS_KEY;
            String hold = properties.getProperty(holdKey);
            // El plazo para pagar es obligatorio; el límite de unidades no.
            if (hold == null) {
                throw new IllegalStateException("No policy defined for category " + category
                        + " (missing " + holdKey + ")");
            }
            try {
                Duration holdTime = Duration.parse(hold.trim());
                String max = properties.getProperty(maxKey);
                parsed.put(category, max == null || max.isBlank()
                        ? CategoryPolicy.of(holdTime)
                        : CategoryPolicy.of(holdTime, Integer.parseInt(max.trim())));
            } catch (DateTimeParseException | IllegalArgumentException e) {
                // Aquí caen la duración mal escrita (DateTimeParseException) y los números inválidos
                // o no positivos (IllegalArgumentException). Se avisa de qué categoría es el problema.
                throw new IllegalStateException("Invalid policy for category " + category + ": " + e.getMessage(), e);
            }
        }
        return new CategoryPolicies(parsed);
    }
}
