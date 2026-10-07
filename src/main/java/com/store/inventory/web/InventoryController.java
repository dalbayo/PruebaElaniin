package com.store.inventory.web;

import com.store.inventory.api.InventoryService;
import com.store.inventory.web.ApiModels.AddStockRequest;
import com.store.inventory.web.ApiModels.AvailabilityResponse;
import com.store.inventory.web.ApiModels.ConfirmationResponse;
import com.store.inventory.web.ApiModels.ErrorResponse;
import com.store.inventory.web.ApiModels.ProductResponse;
import com.store.inventory.web.ApiModels.RegisterProductRequest;
import com.store.inventory.web.ApiModels.ReservationResponse;
import com.store.inventory.web.ApiModels.ReserveRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone por HTTP las operaciones del servicio de inventario, bajo la ruta {@code /api}.
 *
 * <p>Es un adaptador delgado: comprueba que lleguen los campos obligatorios y le pasa el trabajo a
 * {@link InventoryService}. Aquí no hay reglas de negocio. Cuando el servicio rechaza algo (falta de
 * stock, límite excedido, etc.), {@link ApiExceptionHandler} convierte el error en la respuesta HTTP
 * correspondiente.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Inventario", description = "Productos, stock, reservas y confirmaciones")
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    /**
     * {@code POST /api/products}: registra un producto con su categoría. Responde 201 con el SKU y la
     * categoría. Repetir el registro con la misma categoría también responde 201; con otra categoría
     * responde 400.
     */
    @Operation(summary = "Registrar un producto",
            description = "Registra el SKU con su categoría. Repetir el registro con la misma categoría "
                    + "responde 201 igual; con otra categoría responde 400.")
    @ApiResponse(responseCode = "201", description = "Producto registrado")
    @ApiResponse(responseCode = "400", description = "SKU o categoría ausentes, categoría inexistente, "
            + "cuerpo mal formado o SKU ya registrado con otra categoría",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse registerProduct(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = {
                    @ExampleObject(name = "STANDARD (15 min, sin límite)",
                            value = "{\"sku\": \"STD-001\", \"category\": \"STANDARD\"}"),
                    @ExampleObject(name = "PRE_ORDER (24 h, sin límite)",
                            value = "{\"sku\": \"PRE-001\", \"category\": \"PRE_ORDER\"}"),
                    @ExampleObject(name = "FLASH_SALE (5 min, máximo 2 unidades)",
                            value = "{\"sku\": \"FLASH-001\", \"category\": \"FLASH_SALE\"}")}))
            @RequestBody RegisterProductRequest request) {
        // Si no llega cuerpo, Spring ya responde 400 antes de llegar aquí; la comprobación de null es
        // una defensa extra.
        String sku = required(request == null ? null : request.sku(), "sku");
        var category = required(request.category(), "category");
        service.registerProduct(sku, category);
        return new ProductResponse(sku, category);
    }

    /**
     * {@code POST /api/products/{sku}/stock}: agrega unidades a un producto registrado. Responde 200
     * con las unidades disponibles <b>después</b> de agregar (no con la cantidad agregada).
     */
    @Operation(summary = "Agregar stock",
            description = "Suma unidades a un producto ya registrado y devuelve las disponibles después "
                    + "de sumar, no la cantidad agregada.")
    @ApiResponse(responseCode = "200", description = "Stock agregado")
    @ApiResponse(responseCode = "400", description = "Cantidad ausente, cero o negativa, o producto no "
            + "registrado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/products/{sku}/stock")
    public AvailabilityResponse addStock(
            @Parameter(description = "SKU de un producto ya registrado", example = "STD-001")
            @PathVariable("sku") String sku,
            @RequestBody AddStockRequest request) {
        int quantity = required(request == null ? null : request.quantity(), "quantity");
        service.addStock(sku, quantity);
        return new AvailabilityResponse(sku, service.available(sku));
    }

    /**
     * {@code GET /api/products/{sku}/availability}: consulta cuántas unidades se pueden reservar.
     * Para un producto que no existe responde 200 con 0 unidades, no 404, como indica el contrato.
     */
    @Operation(summary = "Consultar disponibilidad",
            description = "Unidades que se pueden reservar ahora. Un producto desconocido responde 200 "
                    + "con 0 unidades, no 404.")
    @ApiResponse(responseCode = "200", description = "Unidades disponibles")
    @GetMapping("/products/{sku}/availability")
    public AvailabilityResponse availability(
            @Parameter(description = "SKU del producto", example = "STD-001")
            @PathVariable("sku") String sku) {
        return new AvailabilityResponse(sku, service.available(sku));
    }

    /**
     * {@code POST /api/reservations}: reserva las unidades de un pedido. Responde 201 con la reserva y
     * su fecha de vencimiento.
     *
     * <p>Si la app reenvía el mismo pedido, también responde 201 con la misma reserva (mismo
     * vencimiento): la API no distingue entre "recién creada" y "ya existía".
     */
    @Operation(summary = "Reservar unidades para un pedido",
            description = "Descuenta las unidades de la disponibilidad hasta el vencimiento que marca la "
                    + "categoría. Reenviar el mismo pedido devuelve la misma reserva sin descontar dos "
                    + "veces.")
    @ApiResponse(responseCode = "201", description = "Reserva creada (o la ya existente, si se reenvió)")
    @ApiResponse(responseCode = "400", description = "Datos ausentes o inválidos, o el pedido ya existe "
            + "con otra cantidad u otro producto",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Stock insuficiente o producto desconocido",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "Se supera el límite de unidades por pedido de la "
            + "categoría (FLASH_SALE: 2)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(examples = {
                    @ExampleObject(name = "STANDARD: 3 unidades (vence en 15 min)",
                            value = "{\"orderId\": \"ORDER-1\", \"sku\": \"STD-001\", \"quantity\": 3}"),
                    @ExampleObject(name = "PRE_ORDER: 4 unidades (vence en 24 h)",
                            value = "{\"orderId\": \"ORDER-2\", \"sku\": \"PRE-001\", \"quantity\": 4}"),
                    @ExampleObject(name = "FLASH_SALE: 2 unidades (vence en 5 min)",
                            value = "{\"orderId\": \"ORDER-3\", \"sku\": \"FLASH-001\", \"quantity\": 2}"),
                    @ExampleObject(name = "FLASH_SALE: 3 unidades (debe dar 422)",
                            value = "{\"orderId\": \"ORDER-4\", \"sku\": \"FLASH-001\", \"quantity\": 3}")}))
            @RequestBody ReserveRequest request) {
        String orderId = required(request == null ? null : request.orderId(), "orderId");
        String sku = required(request.sku(), "sku");
        int quantity = required(request.quantity(), "quantity");
        return ReservationResponse.from(service.reserve(orderId, sku, quantity));
    }

    /**
     * {@code POST /api/reservations/{orderId}/confirm}: confirma el pago de un pedido. Responde 200
     * con el estado {@code CONFIRMED}. Confirmar dos veces el mismo pedido también responde 200.
     */
    @Operation(summary = "Confirmar el pago de un pedido",
            description = "Convierte la reserva en venta: deja de vencer. Confirmar dos veces el mismo "
                    + "pedido también responde 200.")
    @ApiResponse(responseCode = "200", description = "Pedido confirmado")
    @ApiResponse(responseCode = "409", description = "Pedido desconocido o reserva ya vencida",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/reservations/{orderId}/confirm")
    public ConfirmationResponse confirm(
            @Parameter(description = "Pedido con una reserva activa", example = "ORDER-1")
            @PathVariable("orderId") String orderId) {
        service.confirm(orderId);
        return new ConfirmationResponse(orderId, "CONFIRMED");
    }

    /**
     * Devuelve el valor, o falla con un error 400 y un mensaje claro si no llegó. Sin esto, un campo
     * ausente llegaría como {@code null} al servicio, que fallaría con un {@code NullPointerException}
     * (un error 500 que no es culpa del servidor).
     */
    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
