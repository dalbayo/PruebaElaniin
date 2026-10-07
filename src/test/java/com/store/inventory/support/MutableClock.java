package com.store.inventory.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Reloj para pruebas: la hora solo cambia cuando se lo pides con {@link #advance}. Permite simular
 * que pasan 5 o 15 minutos, o incluso días, sin esperar de verdad.
 */
public final class MutableClock extends Clock {

    // volatile porque las pruebas de concurrencia lo leen desde varios hilos.
    private volatile Instant now;

    /** @param start hora inicial del reloj */
    public MutableClock(Instant start) {
        this.now = start;
    }

    /** Adelanta el reloj la duración indicada. */
    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    // La zona horaria no importa en las pruebas: el reloj siempre trabaja en UTC.
    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
