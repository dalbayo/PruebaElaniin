package com.store.inventory.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.ProductCategory;
import com.store.inventory.domain.ProductStock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Prueba el repositorio en memoria: que solo un hilo a la vez use el stock de un mismo producto,
 * que productos distintos no se bloqueen entre sí, que el bloqueo se suelte aunque la acción falle,
 * y que el registro de productos y la asociación de pedidos funcionen bien con muchos hilos.
 */
class InMemoryInventoryRepositoryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private InMemoryInventoryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryInventoryRepository();
    }

    @Test
    void withProductOnUnknownSkuReturnsEmptyAndSkipsTheAction() {
        AtomicInteger calls = new AtomicInteger();
        Optional<Integer> result = repository.withProduct("NOPE", stock -> calls.incrementAndGet());
        assertTrue(result.isEmpty());
        assertEquals(0, calls.get());
    }

    @Test
    void withProductReturnsTheActionResult() {
        repository.registerIfAbsent("SKU-1", ProductCategory.STANDARD);
        Optional<Integer> available = repository.withProduct("SKU-1", stock -> {
            stock.addUnits(5);
            return stock.availableAt(NOW);
        });
        assertEquals(Optional.of(5), available);
    }

    @Test
    void stateSurvivesBetweenCalls() {
        repository.registerIfAbsent("SKU-1", ProductCategory.STANDARD);
        repository.withProduct("SKU-1", stock -> {
            stock.addUnits(5);
            return true;
        });
        assertEquals(Optional.of(5), repository.withProduct("SKU-1", stock -> stock.availableAt(NOW)));
    }

    @Test
    void registerIfAbsentKeepsTheFirstCategory() {
        assertEquals(ProductCategory.STANDARD,
                repository.registerIfAbsent("SKU-1", ProductCategory.STANDARD));
        assertEquals(ProductCategory.STANDARD,
                repository.registerIfAbsent("SKU-1", ProductCategory.FLASH_SALE));
    }

    @Test
    void concurrentRegisterCreatesASingleProduct() throws Exception {
        Set<ProductStock> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        runConcurrently(50, () -> {
            repository.registerIfAbsent("SKU-1", ProductCategory.STANDARD);
            repository.withProduct("SKU-1", seen::add);
            return null;
        });
        assertEquals(1, new HashSet<>(seen).size());
    }

    @Test
    void withProductIsMutuallyExclusivePerSku() throws Exception {
        repository.registerIfAbsent("SKU-1", ProductCategory.STANDARD);
        runConcurrently(100, () -> repository.withProduct("SKU-1", stock -> {
            stock.addUnits(1);
            return true;
        }));
        assertEquals(Optional.of(100), repository.withProduct("SKU-1", stock -> stock.availableAt(NOW)));
    }

    /**
     * Mientras un hilo mantiene bloqueado el producto A, otro hilo debe poder trabajar con el producto
     * B sin esperar. Si el bloqueo fuera global, este test se quedaría esperando.
     */
    @Test
    void differentSkusDoNotBlockEachOther() throws Exception {
        repository.registerIfAbsent("SKU-A", ProductCategory.STANDARD);
        repository.registerIfAbsent("SKU-B", ProductCategory.STANDARD);
        CountDownLatch insideA = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holdingA = executor.submit(() -> repository.withProduct("SKU-A", stock -> {
                insideA.countDown();
                await(releaseA);
                return true;
            }));
            assertTrue(insideA.await(5, TimeUnit.SECONDS));

            Future<Optional<Boolean>> onB = executor.submit(() -> repository.withProduct("SKU-B", stock -> true));
            assertEquals(Optional.of(true), onB.get(5, TimeUnit.SECONDS));

            releaseA.countDown();
            holdingA.get(5, TimeUnit.SECONDS);
        } finally {
            releaseA.countDown();
            executor.shutdownNow();
        }
    }

    /** Si la acción lanza una excepción, el producto no puede quedar bloqueado para siempre. */
    @Test
    void lockIsReleasedWhenTheActionThrows() {
        repository.registerIfAbsent("SKU-1", ProductCategory.STANDARD);
        assertThrows(IllegalStateException.class, () -> repository.withProduct("SKU-1", stock -> {
            throw new IllegalStateException("boom");
        }));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Optional<Boolean> result = repository.withProduct("SKU-1", stock -> true);
            assertEquals(Optional.of(true), result);
        });
    }

    @Test
    void bindOrderIfAbsentReturnsTheExistingSku() {
        assertEquals(Optional.empty(), repository.bindOrderIfAbsent("ORDER-1", "SKU-1"));
        assertEquals(Optional.of("SKU-1"), repository.bindOrderIfAbsent("ORDER-1", "SKU-2"));
        assertEquals(Optional.of("SKU-1"), repository.findSkuByOrderId("ORDER-1"));
    }

    @Test
    void findSkuByUnknownOrderIsEmpty() {
        assertTrue(repository.findSkuByOrderId("NOPE").isEmpty());
    }

    @Test
    void concurrentBindHasASingleWinner() throws Exception {
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger counter = new AtomicInteger();
        runConcurrently(50, () -> {
            String sku = "SKU-" + counter.incrementAndGet();
            if (repository.bindOrderIfAbsent("ORDER-1", sku).isEmpty()) {
                winners.incrementAndGet();
            }
            return null;
        });
        assertEquals(1, winners.get());
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> repository.registerIfAbsent(null, ProductCategory.STANDARD));
        assertThrows(NullPointerException.class, () -> repository.registerIfAbsent("SKU-1", null));
        assertThrows(NullPointerException.class, () -> repository.withProduct(null, stock -> true));
        assertThrows(NullPointerException.class, () -> repository.withProduct("SKU-1", null));
        assertThrows(NullPointerException.class, () -> repository.findSkuByOrderId(null));
        assertThrows(NullPointerException.class, () -> repository.bindOrderIfAbsent(null, "SKU-1"));
        assertThrows(NullPointerException.class, () -> repository.bindOrderIfAbsent("ORDER-1", null));
    }

    @Test
    void sameStockInstanceIsReturnedForTheSameSku() {
        repository.registerIfAbsent("SKU-1", ProductCategory.STANDARD);
        ProductStock first = repository.withProduct("SKU-1", stock -> stock).orElseThrow();
        ProductStock second = repository.withProduct("SKU-1", stock -> stock).orElseThrow();
        assertSame(first, second);
    }

    /** Espera la señal del latch (hasta 10 segundos) sin lanzar la excepción de interrupción. */
    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Ejecuta la tarea en {@code threads} hilos que arrancan a la vez; falla si se pasa de tiempo. */
    private static <T> void runConcurrently(int threads, Callable<T> task) throws Exception {
        assertTimeoutPreemptively(TIMEOUT, () -> {
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<T>> futures = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        return task.call();
                    }));
                }
                start.countDown();
                for (Future<T> future : futures) {
                    future.get();
                }
            } finally {
                executor.shutdownNow();
            }
        });
    }
}
