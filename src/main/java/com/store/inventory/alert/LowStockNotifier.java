package com.store.inventory.alert;

import com.store.inventory.api.StockAlertListener;
import com.store.inventory.domain.ProductStock;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decide cuándo hay que avisar a compras de que un producto tiene poco stock, y evita repetir el
 * mismo aviso una y otra vez.
 *
 * <p>Se avisa cuando las unidades disponibles llegan al umbral o quedan por debajo (5 por defecto).
 * Después de avisar, ese producto no vuelve a generar avisos hasta que se recupere, es decir, hasta
 * que sus unidades disponibles vuelvan a superar el umbral.
 *
 * <p>Esta clase no guarda estado propio. La marca "ya avisamos" vive en {@link ProductStock}, junto
 * al stock del producto. Así, si el inventario pasa a una base de datos compartida por varias
 * instancias, todas ven la misma marca y no se duplican los avisos.
 *
 * <p>Cómo se usa, siempre sobre un solo producto:
 * <ol>
 *   <li>Con el producto bloqueado, al empezar una reserva y después de agregar stock:
 *       {@link #rearmIfRestocked}.</li>
 *   <li>Con el producto bloqueado, después de reservar: {@link #evaluate}, que decide si hay aviso.</li>
 *   <li>Ya sin el bloqueo: {@link #send}, con el aviso que devolvió {@code evaluate}.</li>
 * </ol>
 *
 * <p>El vencimiento de una reserva no es un evento que avise a nadie: se nota la próxima vez que se
 * llama a {@code rearmIfRestocked}, porque entonces sus unidades ya cuentan como disponibles.
 */
public final class LowStockNotifier {

    /** Umbral que se usa si no se configura otro: se avisa cuando quedan 5 unidades o menos. */
    public static final int DEFAULT_THRESHOLD = 5;

    static final String SETTINGS_RESOURCE = "inventory.properties";
    static final String THRESHOLD_KEY = "low-stock-threshold";

    private static final Logger LOG = Logger.getLogger(LowStockNotifier.class.getName());

    private final StockAlertListener listener;
    private final int threshold;

    /**
     * @param listener canal al que se entregan los avisos (obligatorio)
     * @param threshold unidades disponibles a partir de las cuales se avisa; no puede ser negativo
     */
    public LowStockNotifier(StockAlertListener listener, int threshold) {
        this.listener = Objects.requireNonNull(listener, "listener");
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must not be negative");
        }
        this.threshold = threshold;
    }

    /**
     * Crea el notificador leyendo el umbral del archivo {@code inventory.properties} (clave
     * {@code low-stock-threshold}). Si el archivo no existe o no trae esa clave, usa el umbral por
     * defecto.
     */
    public static LowStockNotifier withDefaults(StockAlertListener listener) {
        try (InputStream in = LowStockNotifier.class.getClassLoader().getResourceAsStream(SETTINGS_RESOURCE)) {
            Properties properties = new Properties();
            // Si el archivo no está, se sigue con propiedades vacías y se termina usando el umbral
            // por defecto.
            if (in != null) {
                properties.load(in);
            }
            return fromProperties(listener, properties);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + SETTINGS_RESOURCE, e);
        }
    }

    /**
     * Crea el notificador a partir de unas propiedades que ya están cargadas. Si falta la clave
     * {@code low-stock-threshold} (o viene vacía), usa el umbral por defecto.
     *
     * @throws IllegalStateException si el valor no es un número entero, o es negativo
     */
    public static LowStockNotifier fromProperties(StockAlertListener listener, Properties properties) {
        String value = properties.getProperty(THRESHOLD_KEY);
        if (value == null || value.isBlank()) {
            return new LowStockNotifier(listener, DEFAULT_THRESHOLD);
        }
        try {
            return new LowStockNotifier(listener, Integer.parseInt(value.trim()));
        } catch (IllegalArgumentException e) {
            // Aquí llegan tanto un texto que no es número (NumberFormatException) como un umbral
            // negativo (lo rechaza el constructor). Se falla al arrancar para que un valor mal escrito
            // se note enseguida y no cuando ya haya clientes comprando.
            throw new IllegalStateException("Invalid " + THRESHOLD_KEY + ": " + value, e);
        }
    }

    public int threshold() {
        return threshold;
    }

    /**
     * Quita la marca "ya avisamos" cuando el producto se recuperó, es decir, cuando sus unidades
     * disponibles vuelven a estar por encima del umbral. A partir de ahí, si vuelve a bajar, se
     * podrá avisar otra vez.
     *
     * <p>Se llama al empezar una reserva y después de agregar stock. Si el producto sigue en el umbral
     * o por debajo, la marca se mantiene: reponer unas pocas unidades que no sacan al producto de
     * esa zona no justifica un aviso nuevo e igual al anterior. Las reservas vencidas también cuentan
     * como recuperación, porque sus unidades vuelven a estar disponibles.
     *
     * @param stock producto que se está revisando
     * @param now momento actual, para saber qué reservas siguen vigentes
     */
    public void rearmIfRestocked(ProductStock stock, Instant now) {
        if (stock.lowStockAlerted() && stock.availableAt(now) > threshold) {
            stock.lowStockAlerted(false);
        }
    }

    /**
     * Decide si hay que avisar. Se llama después de reservar, cuando las unidades disponibles
     * acaban de bajar.
     *
     * <p>Si hay aviso, deja marcado el producto como "ya avisado". Por eso devuelve cada aviso una sola
     * vez, hasta que {@link #rearmIfRestocked} lo vuelva a habilitar.
     *
     * @param stock producto que acaba de cambiar
     * @param now momento actual, para saber qué reservas siguen vigentes
     * @return el aviso que hay que enviar, o vacío si no hace falta (todavía hay stock de sobra o ya
     *         se avisó antes)
     */
    public Optional<LowStockAlert> evaluate(ProductStock stock, Instant now) {
        int available = stock.availableAt(now);
        if (available > threshold || stock.lowStockAlerted()) {
            return Optional.empty();
        }
        // La marca se guarda ya, antes de entregar el aviso. Si el canal falla, ese aviso no se
        // reintenta: se prefiere no repetir avisos a arriesgar duplicados.
        stock.lowStockAlerted(true);
        return Optional.of(new LowStockAlert(stock.sku(), available));
    }

    /**
     * Entrega el aviso al canal configurado. Debe llamarse cuando ya se soltó el bloqueo del
     * producto, porque un canal lento (por ejemplo, un correo) no debe frenar otras operaciones.
     *
     * <p>Si el canal falla, el error se registra en el log y no se propaga: un problema al avisar no
     * debe afectar a quien llamó, por ejemplo a una reserva que ya se hizo bien.
     */
    public void send(LowStockAlert alert) {
        try {
            listener.onLowStock(alert.sku(), alert.availableUnits());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Low stock listener failed for " + alert.sku(), e);
        }
    }
}
