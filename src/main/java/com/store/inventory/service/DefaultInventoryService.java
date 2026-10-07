package com.store.inventory.service;

import com.store.inventory.alert.LowStockAlert;
import com.store.inventory.alert.LowStockNotifier;
import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.domain.ProductStock;
import com.store.inventory.domain.ReservationRecord;
import com.store.inventory.policy.CategoryPolicies;
import com.store.inventory.policy.CategoryPolicy;
import com.store.inventory.repository.InventoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Es la implementación del servicio de inventario que usa la app: registra productos, agrega stock,
 * reserva unidades, confirma pedidos y consulta la disponibilidad.
 *
 * <p>Esta clase se limita a coordinar el flujo; las reglas viven en otras clases: las de cada
 * categoría en {@link CategoryPolicies}, las del stock en {@link ProductStock}, las de los avisos en
 * {@link LowStockNotifier} y el control de acceso simultáneo en {@link InventoryRepository}.
 *
 * <p>Todo lo que toca el stock de un producto ocurre dentro de una sola llamada a
 * {@code withProduct}, y la hora se lee dentro de ella: así un hilo que tuvo que esperar el bloqueo
 * no usa una hora vieja. Los avisos de bajo stock se deciden con el producto bloqueado, pero se
 * envían después de soltarlo.
 */
public final class DefaultInventoryService implements InventoryService {

    private final Clock clock;
    private final InventoryRepository repository;
    private final CategoryPolicies policies;
    private final LowStockNotifier notifier;

    /**
     * @param clock reloj con el que se calcula la hora; se recibe de afuera para poder probar la
     *        expiración de reservas sin esperar
     * @param repository dónde se guarda el stock y a qué producto pertenece cada pedido
     * @param policies plazo para pagar y límite por pedido de cada categoría
     * @param notifier decide cuándo avisar de bajo stock
     */
    public DefaultInventoryService(Clock clock, InventoryRepository repository, CategoryPolicies policies,
                                   LowStockNotifier notifier) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.policies = Objects.requireNonNull(policies, "policies");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    /**
     * Registra un producto con su categoría. Registrarlo dos veces con la misma categoría no hace
     * nada; con otra categoría falla, porque cambiarla en silencio alteraría el plazo y el límite de
     * las reservas que ya existen.
     *
     * @throws IllegalArgumentException si el SKU está en blanco, o si el producto ya estaba registrado
     *         con otra categoría
     */
    @Override
    public void registerProduct(String sku, ProductCategory category) {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(category, "category");
        if (sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        ProductCategory registered = repository.registerIfAbsent(sku, category);
        if (registered != category) {
            throw new IllegalArgumentException(
                    "Product " + sku + " is already registered as " + registered + ", not " + category);
        }
    }

    /**
     * Agrega unidades a un producto ya registrado. Si el producto se recuperó, deja habilitado un
     * nuevo aviso de bajo stock, pero este método nunca envía avisos.
     *
     * @throws IllegalArgumentException si la cantidad no es positiva o el producto no está registrado
     */
    @Override
    public void addStock(String sku, int quantity) {
        Objects.requireNonNull(sku, "sku");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        repository.withProduct(sku, stock -> {
            stock.addUnits(quantity);
            notifier.rearmIfRestocked(stock, clock.instant());
            return Boolean.TRUE;
        }).orElseThrow(() -> new IllegalArgumentException("Product " + sku + " is not registered"));
    }

    /**
     * Aparta las unidades de un pedido y devuelve la reserva, que vence según la categoría del
     * producto.
     *
     * <p>Los reenvíos son seguros: la app reintenta cuando la conexión es lenta, y si el pedido ya
     * tenía una reserva con la misma cantidad se devuelve esa misma, sin descontar otra vez y sin
     * extender su vencimiento.
     *
     * <p>Si al reservar el producto queda con pocas unidades, el aviso de bajo stock se envía al
     * final, ya sin el producto bloqueado.
     *
     * @throws IllegalArgumentException si la cantidad no es positiva, si el pedido ya pertenece a otro
     *         producto, o si ya había reservado otra cantidad
     * @throws com.store.inventory.api.OrderLimitExceededException si la cantidad supera el límite
     *         de la categoría
     * @throws InsufficientStockException si no alcanzan las unidades; también si el producto no existe,
     *         porque un producto desconocido no tiene unidades
     */
    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(sku, "sku");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        // Primera revisión, sin bloqueo: descarta el caso común de un pedido que ya pertenece a otro
        // producto. El caso de dos solicitudes simultáneas se resuelve más abajo, en reserveLocked.
        repository.findSkuByOrderId(orderId).ifPresent(boundSku -> {
            if (!boundSku.equals(sku)) {
                throw new IllegalArgumentException("Order " + orderId + " belongs to product " + boundSku);
            }
        });

        // Un producto desconocido no tiene unidades: se informa como stock insuficiente, como pide el
        // contrato.
        ReserveResult result = repository.withProduct(sku, stock -> reserveLocked(stock, orderId, quantity))
                .orElseThrow(() -> new InsufficientStockException(sku, quantity, 0));

        // El aviso se envía aquí, con el producto ya desbloqueado, para que un canal lento no frene a
        // otros clientes.
        result.alert().ifPresent(notifier::send);
        return result.reservation();
    }

    /**
     * Hace el trabajo de reservar. Solo debe llamarse desde dentro de {@code withProduct}, con el
     * producto bloqueado. En orden:
     * <ol>
     *   <li>lee la hora, re-arma el aviso si el producto se recuperó y limpia reservas vencidas;</li>
     *   <li>si el pedido ya tenía reserva, la devuelve (reenvío);</li>
     *   <li>valida el límite de la categoría y aparta las unidades;</li>
     *   <li>liga el pedido al producto y decide si hay que avisar de bajo stock.</li>
     * </ol>
     */
    private ReserveResult reserveLocked(ProductStock stock, String orderId, int quantity) {
        Instant now = clock.instant();
        // Si las reservas vencidas devolvieron unidades, se habilita de nuevo el aviso; además se
        // limpian las vencidas para no acumular memoria.
        notifier.rearmIfRestocked(stock, now);
        stock.purgeExpired(now);

        // Si el pedido ya tiene reserva es un reenvío: se devuelve la misma, sin descontar de nuevo.
        Optional<ReservationRecord> existing = stock.findReservation(orderId);
        if (existing.isPresent()) {
            ReservationRecord previous = existing.get();
            // Con otra cantidad ya no es un reenvío sino un pedido distinto: se rechaza para no
            // ocultar un error del cliente.
            if (previous.quantity() != quantity) {
                throw new IllegalArgumentException("Order " + orderId + " already reserved "
                        + previous.quantity() + " units, not " + quantity);
            }
            return new ReserveResult(previous.toApi(), Optional.empty());
        }

        // El límite de la categoría se revisa antes que el stock: depende de lo que pide el pedido,
        // no de cuántas unidades hay.
        CategoryPolicy policy = policies.forCategory(stock.category());
        policy.validate(stock.sku(), quantity);
        ReservationRecord record = stock.reserve(orderId, quantity, policy.expiresAt(now), now);

        // Si dos primeras solicitudes del mismo pedido llegan a la vez para productos distintos, solo
        // una logra ligarse: la otra deshace su reserva. El pedido se liga al final y no al principio
        // para no dejar pedidos ligados cuando la reserva falla (por ejemplo, por falta de stock).
        Optional<String> boundSku = repository.bindOrderIfAbsent(orderId, stock.sku());
        if (boundSku.isPresent() && !boundSku.get().equals(stock.sku())) {
            stock.release(orderId);
            throw new IllegalArgumentException("Order " + orderId + " belongs to product " + boundSku.get());
        }
        return new ReserveResult(record.toApi(), notifier.evaluate(stock, now));
    }

    /**
     * Confirma el pago de un pedido: sus unidades quedan vendidas y nunca vuelven al stock.
     * Confirmar dos veces el mismo pedido es seguro.
     *
     * @throws IllegalStateException si el pedido no tiene una reserva activa: no existe, o ya venció
     */
    @Override
    public void confirm(String orderId) {
        Objects.requireNonNull(orderId, "orderId");
        // Al confirmar solo se recibe el número de pedido: hay que buscar a qué producto pertenece.
        String sku = repository.findSkuByOrderId(orderId)
                .orElseThrow(() -> noActiveReservation(orderId));
        // Confirmar no cambia las unidades disponibles (ya se descontaron al reservar), por eso aquí
        // no se evalúa ningún aviso de bajo stock.
        repository.withProduct(sku, stock -> stock.confirm(orderId, clock.instant()))
                .orElseThrow(() -> noActiveReservation(orderId));
    }

    /**
     * Devuelve cuántas unidades se pueden reservar ahora: el stock menos las reservas que siguen
     * esperando pago. Las reservas vencidas no cuentan. Si el producto no existe, devuelve 0.
     */
    @Override
    public int available(String sku) {
        Objects.requireNonNull(sku, "sku");
        // Se lee con el producto bloqueado para no verlo a medio actualizar.
        return repository.withProduct(sku, stock -> stock.availableAt(clock.instant())).orElse(0);
    }

    private static IllegalStateException noActiveReservation(String orderId) {
        return new IllegalStateException("Order " + orderId + " has no active reservation");
    }

    /**
     * Resultado interno de reservar: la reserva y, si corresponde, el aviso de bajo stock. Van
     * juntos porque el aviso se decide con el producto bloqueado pero se envía después de soltarlo.
     */
    private record ReserveResult(Reservation reservation, Optional<LowStockAlert> alert) {
    }
}
