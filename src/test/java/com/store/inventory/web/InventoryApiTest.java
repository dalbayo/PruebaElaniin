package com.store.inventory.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.store.inventory.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Prueba la API REST contra el servicio real, sin abrir un servidor: se simulan las peticiones HTTP
 * ({@code MockMvc}). Comprueba los estados HTTP, los cuerpos de error, los reenvíos, los límites por
 * categoría y el vencimiento de reservas.
 *
 * <p>El contexto de Spring (y con él el inventario en memoria) se comparte entre todos los tests. Por
 * eso cada test usa sus propios SKU y números de pedido, para no interferir con los demás.
 */
@SpringBootTest(properties = {"logging.level.root=ERROR", "spring.main.banner-mode=off"})
@AutoConfigureMockMvc
@Import(InventoryApiTest.TestClockConfig.class)
class InventoryApiTest {

    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");
    /** Contador para generar SKU y pedidos distintos en cada test. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** Reemplaza el reloj real por uno que se adelanta a mano, para probar vencimientos sin esperar. */
    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(START);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MutableClock clock;

    /** Genera un identificador que no se repite en toda la ejecución, como {@code STANDARD-7}. */
    private static String unique(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet();
    }

    private ResultActions postJson(String url, String json) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Registra un producto nuevo de la categoría dada, con ese stock, y devuelve su SKU. */
    private String product(String category, int stock) throws Exception {
        String sku = unique(category);
        postJson("/api/products", "{\"sku\":\"" + sku + "\",\"category\":\"" + category + "\"}")
                .andExpect(status().isCreated());
        if (stock > 0) {
            postJson("/api/products/" + sku + "/stock", "{\"quantity\":" + stock + "}").andExpect(status().isOk());
        }
        return sku;
    }

    private ResultActions reserve(String orderId, String sku, int quantity) throws Exception {
        return postJson("/api/reservations",
                "{\"orderId\":\"" + orderId + "\",\"sku\":\"" + sku + "\",\"quantity\":" + quantity + "}");
    }

    /** Consulta la disponibilidad por la API y comprueba que sea la esperada. */
    private void assertAvailable(String sku, int expected) throws Exception {
        mvc.perform(get("/api/products/" + sku + "/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value(sku))
                .andExpect(jsonPath("$.available").value(expected));
    }

    // productos

    @Test
    void registeringAProductReturnsCreated() throws Exception {
        String sku = unique("REG");
        postJson("/api/products", "{\"sku\":\"" + sku + "\",\"category\":\"STANDARD\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value(sku))
                .andExpect(jsonPath("$.category").value("STANDARD"));
    }

    @Test
    void registeringTwiceWithTheSameCategoryIsHarmless() throws Exception {
        String sku = product("STANDARD", 0);
        postJson("/api/products", "{\"sku\":\"" + sku + "\",\"category\":\"STANDARD\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void registeringWithAnotherCategoryIsABadRequest() throws Exception {
        String sku = product("STANDARD", 0);
        postJson("/api/products", "{\"sku\":\"" + sku + "\",\"category\":\"FLASH_SALE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("already registered")));
    }

    @Test
    void anUnknownCategoryIsABadRequest() throws Exception {
        postJson("/api/products", "{\"sku\":\"" + unique("X") + "\",\"category\":\"NOPE\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aBlankOrMissingSkuIsABadRequest() throws Exception {
        postJson("/api/products", "{\"sku\":\"  \",\"category\":\"STANDARD\"}").andExpect(status().isBadRequest());
        postJson("/api/products", "{\"category\":\"STANDARD\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("sku is required"));
    }

    @Test
    void aMalformedBodyIsABadRequest() throws Exception {
        postJson("/api/products", "{not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // stock y disponibilidad

    @Test
    void addingStockReturnsTheAvailableUnits() throws Exception {
        String sku = product("STANDARD", 0);
        postJson("/api/products/" + sku + "/stock", "{\"quantity\":4}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(4));
        postJson("/api/products/" + sku + "/stock", "{\"quantity\":6}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(10));
    }

    @Test
    void addingZeroNegativeOrMissingQuantityIsABadRequest() throws Exception {
        String sku = product("STANDARD", 0);
        postJson("/api/products/" + sku + "/stock", "{\"quantity\":0}").andExpect(status().isBadRequest());
        postJson("/api/products/" + sku + "/stock", "{\"quantity\":-3}").andExpect(status().isBadRequest());
        postJson("/api/products/" + sku + "/stock", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("quantity is required"));
    }

    @Test
    void addingStockToAnUnregisteredProductIsABadRequest() throws Exception {
        postJson("/api/products/" + unique("GHOST") + "/stock", "{\"quantity\":5}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("not registered")));
    }

    @Test
    void theAvailabilityOfAnUnknownProductIsZero() throws Exception {
        assertAvailable(unique("GHOST"), 0);
    }

    // reservas

    @Test
    void reservingReturnsCreatedWithTheExpirationOfTheCategory() throws Exception {
        String sku = product("STANDARD", 10);
        reserve(unique("O"), sku, 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value(sku))
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.expiresAt").value(clock.instant().plus(Duration.ofMinutes(15)).toString()));
        assertAvailable(sku, 7);
    }

    /**
     * Un reenvío responde 201 igual que la primera vez, con el mismo vencimiento (el de la primera
     * reserva, no uno nuevo), y no descuenta unidades otra vez.
     */
    @Test
    void retryingTheSameOrderReturnsTheSameReservationAndDoesNotDiscountTwice() throws Exception {
        String sku = product("STANDARD", 10);
        String order = unique("O");
        reserve(order, sku, 3).andExpect(status().isCreated());
        clock.advance(Duration.ofMinutes(2));

        reserve(order, sku, 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value(clock.instant().minus(Duration.ofMinutes(2))
                        .plus(Duration.ofMinutes(15)).toString()));
        assertAvailable(sku, 7);
    }

    @Test
    void retryingTheSameOrderWithAnotherQuantityIsABadRequest() throws Exception {
        String sku = product("STANDARD", 10);
        String order = unique("O");
        reserve(order, sku, 3).andExpect(status().isCreated());
        reserve(order, sku, 4).andExpect(status().isBadRequest());
        assertAvailable(sku, 7);
    }

    @Test
    void reservingMoreThanAvailableIsAConflict() throws Exception {
        String sku = product("STANDARD", 2);
        reserve(unique("O"), sku, 3)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertAvailable(sku, 2);
    }

    @Test
    void reservingAnUnknownProductIsAConflict() throws Exception {
        reserve(unique("O"), unique("GHOST"), 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void flashSaleRejectsMoreThanTwoUnitsAndAcceptsTwo() throws Exception {
        String sku = product("FLASH_SALE", 10);
        reserve(unique("O"), sku, 3)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_LIMIT_EXCEEDED"));
        reserve(unique("O"), sku, 2).andExpect(status().isCreated());
        assertAvailable(sku, 8);
    }

    @Test
    void reservingWithInvalidOrMissingFieldsIsABadRequest() throws Exception {
        String sku = product("STANDARD", 10);
        reserve(unique("O"), sku, 0).andExpect(status().isBadRequest());
        postJson("/api/reservations", "{\"sku\":\"" + sku + "\",\"quantity\":1}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("orderId is required"));
        postJson("/api/reservations", "{\"orderId\":\"x\",\"quantity\":1}").andExpect(status().isBadRequest());
        postJson("/api/reservations", "{\"orderId\":\"x\",\"sku\":\"" + sku + "\"}").andExpect(status().isBadRequest());
    }

    // confirmaciones

    @Test
    void confirmingKeepsTheAvailableUnitsAndIsIdempotent() throws Exception {
        String sku = product("STANDARD", 10);
        String order = unique("O");
        reserve(order, sku, 3).andExpect(status().isCreated());

        mvc.perform(post("/api/reservations/" + order + "/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(post("/api/reservations/" + order + "/confirm")).andExpect(status().isOk());
        assertAvailable(sku, 7);
    }

    @Test
    void confirmingAnUnknownOrderIsAConflict() throws Exception {
        mvc.perform(post("/api/reservations/" + unique("NOPE") + "/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    // vencimiento (con el reloj controlable)

    /** FLASH_SALE vence a los 5 minutos: un segundo antes sigue apartada y justo al vencer se libera. */
    @Test
    void anExpiredReservationReleasesItsUnitsAndCannotBeConfirmed() throws Exception {
        String sku = product("FLASH_SALE", 10);
        String order = unique("O");
        reserve(order, sku, 2).andExpect(status().isCreated());
        assertAvailable(sku, 8);

        clock.advance(Duration.ofMinutes(5).minusSeconds(1));
        assertAvailable(sku, 8);
        clock.advance(Duration.ofSeconds(1));
        assertAvailable(sku, 10);

        mvc.perform(post("/api/reservations/" + order + "/confirm"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void aConfirmedReservationNeverExpires() throws Exception {
        String sku = product("FLASH_SALE", 10);
        String order = unique("O");
        reserve(order, sku, 2).andExpect(status().isCreated());
        mvc.perform(post("/api/reservations/" + order + "/confirm")).andExpect(status().isOk());

        clock.advance(Duration.ofDays(2));

        assertAvailable(sku, 8);
    }
}
