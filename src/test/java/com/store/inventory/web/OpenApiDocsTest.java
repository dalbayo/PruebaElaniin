package com.store.inventory.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Comprueba que la documentación Swagger/OpenAPI se publica, describe los cinco endpoints de la API y
 * trae los ejemplos y la guía para validarla. Si alguien cambia una ruta del controlador o quita la
 * dependencia, este test avisa.
 */
@SpringBootTest(properties = {"logging.level.root=ERROR", "spring.main.banner-mode=off"})
@AutoConfigureMockMvc
class OpenApiDocsTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void openApiDocumentListsEveryEndpoint() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("API de reservas de inventario"))
                .andExpect(jsonPath("$.paths", hasKey("/api/products")))
                .andExpect(jsonPath("$.paths", hasKey("/api/products/{sku}/stock")))
                .andExpect(jsonPath("$.paths", hasKey("/api/products/{sku}/availability")))
                .andExpect(jsonPath("$.paths", hasKey("/api/reservations")))
                .andExpect(jsonPath("$.paths", hasKey("/api/reservations/{orderId}/confirm")));
    }

    @Test
    void reserveDocumentsTheErrorCodesOfTheContract() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/reservations'].post.responses", hasKey("201")))
                .andExpect(jsonPath("$.paths['/api/reservations'].post.responses", hasKey("400")))
                .andExpect(jsonPath("$.paths['/api/reservations'].post.responses", hasKey("409")))
                .andExpect(jsonPath("$.paths['/api/reservations'].post.responses", hasKey("422")));
    }

    @Test
    void requestBodiesComeWithOneExamplePerCategory() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/products'].post.requestBody.content['application/json'].examples.length()")
                        .value(3))
                .andExpect(jsonPath("$.paths['/api/reservations'].post.requestBody.content['application/json'].examples.length()")
                        .value(4));
    }

    @Test
    void apiDescriptionIncludesTheValidationGuide() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.description", containsString("Cómo validar paso a paso")));
    }

    @Test
    void swaggerUiPageIsServed() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("swagger")));
    }
}
