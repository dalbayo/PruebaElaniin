package com.store.inventory.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de arranque de la aplicación web. Levanta el servidor y publica el servicio de inventario
 * por HTTP; todas las reglas de negocio viven en los otros paquetes.
 *
 * <p>Spring solo busca componentes dentro de este paquete ({@code web}). Por eso el resto del
 * sistema (políticas, dominio, repositorio, alertas y servicio) no depende de Spring: llega aquí ya
 * armado a través de {@link InventoryConfig}.
 */
@SpringBootApplication
public class InventoryApplication {

    /** Arranca la aplicación. El puerto se configura en {@code application.properties}. */
    public static void main(String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
