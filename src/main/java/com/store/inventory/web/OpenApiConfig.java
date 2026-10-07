package com.store.inventory.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Datos generales que se muestran en Swagger UI (título, versión y descripción de la API).
 *
 * <p>Los endpoints no se listan aquí: springdoc los descubre solo a partir de
 * {@link InventoryController}. Esta clase únicamente le pone nombre a la documentación.
 */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI inventoryOpenApi() {
        return new OpenAPI().info(new Info()
                .title("API de reservas de inventario")
                .version("1.0.0")
                .description("""
                        Registra productos, agrega stock, reserva unidades para un pedido y confirma el pago.
                        Todos los errores devuelven el mismo cuerpo: `status`, `code` y `message`.

                        ## Reglas por categoría

                        | Categoría | Plazo para pagar | Máximo por pedido |
                        | --- | --- | --- |
                        | STANDARD | 15 minutos | sin límite |
                        | PRE_ORDER | 24 horas | sin límite |
                        | FLASH_SALE | 5 minutos | 2 unidades |

                        ## Cómo validar paso a paso

                        Cada operación trae ejemplos listos para elegir en el cuerpo de la petición. Usa **Try it out**, elige el ejemplo y pulsa **Execute**.

                        1. `POST /api/products`: registra `STD-001`, `PRE-001` y `FLASH-001` (un ejemplo por categoría).
                        2. `POST /api/products/{sku}/stock`: agrega `{"quantity": 10}` a cada uno.
                        3. `POST /api/reservations`: reserva con los ejemplos. Debe dar **201** y un `expiresAt` coherente con la categoría. El ejemplo de **FLASH_SALE con 3 unidades** debe dar **422**.
                        4. `GET /api/products/{sku}/availability`: tras reservar 3 de `STD-001` deben quedar **7**.
                        5. Reenvía la misma reserva: devuelve la misma sin descontar otra vez.
                        6. `POST /api/reservations/{orderId}/confirm` con `ORDER-1`: **200**. La disponibilidad no cambia.
                        7. Aviso de bajo stock: deja un producto en 5 unidades o menos y revisa el log de la aplicación. Debe aparecer una sola línea `Low stock alert`, y no repetirse hasta reabastecer.
                        """));
    }
}
