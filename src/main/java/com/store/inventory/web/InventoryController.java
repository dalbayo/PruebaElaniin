package com.store.inventory.web;

import com.store.inventory.api.InventoryService;
import com.store.inventory.web.ApiModels.AddStockRequest;
import com.store.inventory.web.ApiModels.AvailabilityResponse;
import com.store.inventory.web.ApiModels.ConfirmationResponse;
import com.store.inventory.web.ApiModels.ProductResponse;
import com.store.inventory.web.ApiModels.RegisterProductRequest;
import com.store.inventory.web.ApiModels.ReservationResponse;
import com.store.inventory.web.ApiModels.ReserveRequest;
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
    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse registerProduct(@RequestBody RegisterProductRequest request) {
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
    @PostMapping("/products/{sku}/stock")
    public AvailabilityResponse addStock(@PathVariable("sku") String sku, @RequestBody AddStockRequest request) {
        int quantity = required(request == null ? null : request.quantity(), "quantity");
        service.addStock(sku, quantity);
        return new AvailabilityResponse(sku, service.available(sku));
    }

    /**
     * {@code GET /api/products/{sku}/availability}: consulta cuántas unidades se pueden reservar.
     * Para un producto que no existe responde 200 con 0 unidades, no 404, como indica el contrato.
     */
    @GetMapping("/products/{sku}/availability")
    public AvailabilityResponse availability(@PathVariable("sku") String sku) {
        return new AvailabilityResponse(sku, service.available(sku));
    }

    /**
     * {@code POST /api/reservations}: reserva las unidades de un pedido. Responde 201 con la reserva y
     * su fecha de vencimiento.
     *
     * <p>Si la app reenvía el mismo pedido, también responde 201 con la misma reserva (mismo
     * vencimiento): la API no distingue entre "recién creada" y "ya existía".
     */
    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(@RequestBody ReserveRequest request) {
        String orderId = required(request == null ? null : request.orderId(), "orderId");
        String sku = required(request.sku(), "sku");
        int quantity = required(request.quantity(), "quantity");
        return ReservationResponse.from(service.reserve(orderId, sku, quantity));
    }

    /**
     * {@code POST /api/reservations/{orderId}/confirm}: confirma el pago de un pedido. Responde 200
     * con el estado {@code CONFIRMED}. Confirmar dos veces el mismo pedido también responde 200.
     */
    @PostMapping("/reservations/{orderId}/confirm")
    public ConfirmationResponse confirm(@PathVariable("orderId") String orderId) {
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
