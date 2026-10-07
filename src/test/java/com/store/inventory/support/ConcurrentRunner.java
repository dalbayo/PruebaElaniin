package com.store.inventory.support;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Herramienta de las pruebas de concurrencia: ejecuta varias tareas en hilos distintos y las suelta
 * todas en el mismo instante, para que compitan de verdad.
 *
 * <p>Si algo se bloquea (por ejemplo, un interbloqueo), la prueba falla por tiempo límite en lugar de
 * dejar colgada la compilación.
 */
public final class ConcurrentRunner {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private ConcurrentRunner() {
    }

    /**
     * Resultado de una tarea: su valor, o la excepción que lanzó. Así la prueba puede contar cuántas
     * tuvieron éxito y cuántas fallaron, y por qué.
     */
    public record Outcome<T>(T value, Throwable error) {

        public boolean succeeded() {
            return error == null;
        }

        public boolean failedWith(Class<? extends Throwable> type) {
            return type.isInstance(error);
        }
    }

    /**
     * Ejecuta todas las tareas a la vez y espera a que terminen.
     *
     * @return un resultado por tarea, en el mismo orden en que se recibieron
     */
    public static <T> List<Outcome<T>> runAll(List<? extends Callable<T>> tasks) {
        return assertTimeoutPreemptively(TIMEOUT, () -> execute(tasks));
    }

    private static <T> List<Outcome<T>> execute(List<? extends Callable<T>> tasks) throws Exception {
        // Un hilo por tarea: si hubiera menos, algunas tareas esperarían a otras y no competirían.
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            // "ready" avisa que todos los hilos ya están listos; "start" es la señal de salida común.
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new Outcome<>(task.call(), null);
                    } catch (Throwable error) {
                        // Se guarda el error como resultado en vez de perderlo dentro del hilo.
                        return new Outcome<>(null, error);
                    }
                }));
            }
            ready.await();
            start.countDown();

            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }
}
