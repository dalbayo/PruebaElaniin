package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.alert.LowStockAlert;
import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.support.ConcurrentRunner;
import com.store.inventory.support.ConcurrentRunner.Outcome;
import com.store.inventory.support.MutableClock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Muchos clientes comprando a la vez. Es la prueba de que no hay sobreventa ni duplicados cuando
 * varios hilos compiten por el mismo producto.
 *
 * <p>El reloj está congelado: nada vence durante un test, así que cualquier diferencia con el
 * resultado esperado solo puede venir de una carrera entre hilos. Varios escenarios se repiten
 * (20 o 200 veces) porque una carrera puede no aparecer en una sola ejecución.
 */
class ConcurrencyTest {

    private static final Instant START = Instant.parse("2026-01-01T10:00:00Z");

    /** Avisos de bajo stock recibidos; es una lista segura para que la escriban varios hilos. */
    private final List<LowStockAlert> alerts = new CopyOnWriteArrayList<>();
    private MutableClock clock;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        service = Inventory.create(clock, (sku, available) -> alerts.add(new LowStockAlert(sku, available)));
    }

    /** Registra un producto STANDARD con las unidades indicadas. */
    private void productWithStock(String sku, int units) {
        service.registerProduct(sku, ProductCategory.STANDARD);
        service.addStock(sku, units);
    }

    /** Una tarea que intenta reservar; la ejecutará {@link ConcurrentRunner} junto con las demás. */
    private Callable<Reservation> reserve(String orderId, String sku, int quantity) {
        return () -> service.reserve(orderId, sku, quantity);
    }

    /** Cuenta cuántas tareas fallaron con la excepción indicada. */
    private static long count(List<? extends Outcome<?>> outcomes, Class<? extends Throwable> failure) {
        return outcomes.stream().filter(outcome -> outcome.failedWith(failure)).count();
    }

    /**
     * 200 clientes piden 1 unidad cada uno y solo hay 50. Deben ganar exactamente 50 y los otros 150
     * recibir "stock insuficiente". Detecta sobreventa por falta de exclusión.
     */
    @RepeatedTest(20)
    void twoHundredCustomersCompeteForFiftyUnitsAndExactlyFiftyWin() {
        productWithStock("SKU-1", 50);
        List<Callable<Reservation>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            tasks.add(reserve("ORDER-" + i, "SKU-1", 1));
        }

        List<Outcome<Reservation>> outcomes = ConcurrentRunner.runAll(tasks);

        long winners = outcomes.stream().filter(Outcome::succeeded).count();
        assertEquals(50, winners);
        assertEquals(150, count(outcomes, InsufficientStockException.class));
        assertEquals(0, service.available("SKU-1"));
    }

    /** Los 50 ganadores confirman a la vez: ninguno falla y no se descuenta de más. */
    @Test
    void theFiftyWinnersCanAllConfirmAtTheSameTime() {
        productWithStock("SKU-1", 50);
        List<Callable<Reservation>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            tasks.add(reserve("ORDER-" + i, "SKU-1", 1));
        }
        List<String> winners = ConcurrentRunner.runAll(tasks).stream()
                .filter(Outcome::succeeded)
                .map(outcome -> outcome.value().orderId())
                .toList();

        List<Callable<Boolean>> confirmations = winners.stream()
                .<Callable<Boolean>>map(orderId -> () -> {
                    service.confirm(orderId);
                    return true;
                })
                .toList();
        List<Outcome<Boolean>> outcomes = ConcurrentRunner.runAll(confirmations);

        assertEquals(50, outcomes.stream().filter(Outcome::succeeded).count());
        assertEquals(0, service.available("SKU-1"));
    }

    /**
     * La app reenvía el mismo pedido 100 veces a la vez. Todas deben recibir la misma reserva y las
     * unidades descontarse una sola vez. Detecta reservas duplicadas.
     */
    @RepeatedTest(20)
    void aHundredRetriesOfTheSameOrderReserveOnlyOnce() {
        productWithStock("SKU-1", 10);
        List<Callable<Reservation>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(reserve("ORDER-1", "SKU-1", 3));
        }

        List<Outcome<Reservation>> outcomes = ConcurrentRunner.runAll(tasks);

        assertTrue(outcomes.stream().allMatch(Outcome::succeeded));
        Set<Reservation> distinct = new HashSet<>();
        outcomes.forEach(outcome -> distinct.add(outcome.value()));
        assertEquals(1, distinct.size());
        assertEquals(7, service.available("SKU-1"));
    }

    /**
     * 100 clientes reservan 1 unidad cada uno y el producto cruza el umbral de aviso. Compras debe
     * recibir exactamente un aviso, con 5 unidades. Detecta avisos duplicados.
     */
    @RepeatedTest(20)
    void crossingTheThresholdWithManyThreadsAlertsExactlyOnce() {
        productWithStock("SKU-1", 100);
        List<Callable<Reservation>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(reserve("ORDER-" + i, "SKU-1", 1));
        }

        ConcurrentRunner.runAll(tasks);

        assertEquals(List.of(new LowStockAlert("SKU-1", 5)), alerts);
    }

    /**
     * Pedidos de 1 a 3 unidades sobre un mismo producto. Las unidades aceptadas más las disponibles
     * deben sumar siempre el stock inicial. Detecta errores de cuenta que no se ven con cantidades de 1.
     */
    @Test
    void mixedQuantitiesNeverOversellAndTheCountsAddUp() {
        productWithStock("SKU-1", 200);
        // Semilla fija: los pedidos son "aleatorios" pero iguales en cada ejecución.
        Random random = new Random(42);
        List<Integer> quantities = new ArrayList<>();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            int quantity = 1 + random.nextInt(3);
            quantities.add(quantity);
            String orderId = "ORDER-" + i;
            tasks.add(() -> {
                service.reserve(orderId, "SKU-1", quantity);
                return quantity;
            });
        }

        List<Outcome<Integer>> outcomes = ConcurrentRunner.runAll(tasks);

        int accepted = outcomes.stream().filter(Outcome::succeeded).mapToInt(Outcome::value).sum();
        long insufficient = count(outcomes, InsufficientStockException.class);
        long succeeded = outcomes.stream().filter(Outcome::succeeded).count();
        assertEquals(outcomes.size(), succeeded + insufficient);
        assertTrue(accepted <= 200);
        assertEquals(200 - accepted, service.available("SKU-1"));
    }

    /**
     * 20 productos con 5 unidades cada uno y 400 clientes repartidos entre ellos. Cada producto debe
     * vender exactamente sus 5 unidades: el bloqueo de un producto no puede afectar a otro.
     */
    @Test
    void manyProductsInParallelEachSellExactlyTheirOwnStock() {
        int products = 20;
        for (int p = 0; p < products; p++) {
            productWithStock("SKU-" + p, 5);
        }
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            String sku = "SKU-" + (i % products);
            String orderId = "ORDER-" + i;
            tasks.add(() -> {
                service.reserve(orderId, sku, 1);
                return sku;
            });
        }

        List<Outcome<String>> outcomes = ConcurrentRunner.runAll(tasks);

        Map<String, Long> soldPerProduct = outcomes.stream()
                .filter(Outcome::succeeded)
                .collect(Collectors.groupingBy(Outcome::value, Collectors.counting()));
        assertEquals(products, soldPerProduct.size());
        soldPerProduct.forEach((sku, sold) -> assertEquals(5L, sold, sku));
        for (int p = 0; p < products; p++) {
            assertEquals(0, service.available("SKU-" + p));
        }
    }

    /**
     * Mientras 25 pedidos antiguos se confirman, otros 25 clientes reservan el mismo producto. Todo
     * debe salir bien y no sobrar ni faltar unidades al final.
     */
    @Test
    void confirmingAndReservingOnTheSameProductDoNotInterfere() {
        productWithStock("SKU-1", 50);
        // Primero se dejan 25 reservas hechas, para tener pedidos que confirmar durante la carrera.
        for (int i = 0; i < 25; i++) {
            service.reserve("OLD-" + i, "SKU-1", 1);
        }
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String oldOrder = "OLD-" + i;
            String newOrder = "NEW-" + i;
            tasks.add(() -> {
                service.confirm(oldOrder);
                return true;
            });
            tasks.add(() -> {
                service.reserve(newOrder, "SKU-1", 1);
                return true;
            });
        }
        Collections.shuffle(tasks, new Random(7));

        List<Outcome<Boolean>> outcomes = ConcurrentRunner.runAll(tasks);

        assertTrue(outcomes.stream().allMatch(Outcome::succeeded));
        assertEquals(0, service.available("SKU-1"));
    }

    /**
     * El canal de avisos se queda "pensando" (como un correo lento). Mientras tanto, otro cliente debe
     * poder reservar el mismo producto: el aviso se envía fuera del bloqueo. Si se enviara dentro, el
     * segundo cliente quedaría esperando y el test fallaría por tiempo.
     */
    @Test
    void aSlowAlertListenerDoesNotKeepTheProductLocked() throws Exception {
        // insideListener: el canal ya recibió el aviso. releaseListener: señal para que termine.
        CountDownLatch insideListener = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        InventoryService slow = Inventory.create(clock, (sku, available) -> {
            insideListener.countDown();
            try {
                releaseListener.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        slow.registerProduct("SKU-1", ProductCategory.STANDARD);
        slow.addStock("SKU-1", 10);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Reservation> triggersTheAlert = executor.submit(() -> slow.reserve("ORDER-1", "SKU-1", 5));
            assertTrue(insideListener.await(5, TimeUnit.SECONDS));

            Future<Reservation> anotherCustomer = executor.submit(() -> slow.reserve("ORDER-2", "SKU-1", 1));
            assertNotNull(anotherCustomer.get(5, TimeUnit.SECONDS));

            releaseListener.countDown();
            assertNotNull(triggersTheAlert.get(5, TimeUnit.SECONDS));
        } finally {
            releaseListener.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * El mismo pedido llega a la vez para dos productos distintos (un error del cliente). Solo una
     * reserva debe quedar en pie; la otra se deshace y devuelve sus unidades. Se repite 200 veces
     * porque la carrera que se quiere provocar ocurre solo en algunas ejecuciones.
     */
    @RepeatedTest(200)
    void theSameOrderOnTwoProductsAtOnceLeavesExactlyOneReservation() {
        productWithStock("SKU-A", 10);
        productWithStock("SKU-B", 10);
        List<Callable<Reservation>> tasks = List.of(
                reserve("ORDER-1", "SKU-A", 3),
                reserve("ORDER-1", "SKU-B", 3));

        List<Outcome<Reservation>> outcomes = ConcurrentRunner.runAll(tasks);

        assertEquals(1, outcomes.stream().filter(Outcome::succeeded).count());
        assertEquals(1, count(outcomes, IllegalArgumentException.class));
        boolean winnerIsA = outcomes.get(0).succeeded();
        assertEquals(winnerIsA ? 7 : 10, service.available("SKU-A"));
        assertEquals(winnerIsA ? 10 : 7, service.available("SKU-B"));
        assertDoesNotThrow(() -> service.confirm("ORDER-1"));
        assertEquals(winnerIsA ? 7 : 10, service.available("SKU-A"));
        assertEquals(winnerIsA ? 10 : 7, service.available("SKU-B"));
    }
}
