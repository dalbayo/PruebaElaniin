# Decisiones del proyecto

Este documento explica las decisiones que tomé durante la implementación del servicio de inventario, especialmente en aquellos puntos en los que el enunciado dejaba margen para interpretar el comportamiento.

También dejo claro qué cosas decidí no implementar en esta entrega y qué cambiaría antes de llevar el servicio a producción.

Para cada decisión intento indicar la prueba que la respalda. De esta forma, si más adelante alguien cambia una regla del negocio, queda claro qué comportamiento está cubierto y qué prueba debería alertar si algo deja de funcionar.

El contexto completo del ejercicio y las instrucciones para ejecutar las pruebas están en el [README](README.md).

> **Ajuste posterior:** la documentación Swagger no formaba parte de la entrega inicial. La agregué después, como un ajuste aparte, y lo que cambió está detallado en la sección 6.

Con la aplicación levantada (`java -jar target/inventory-reservations-1.0.0.jar`), la API se puede explorar y probar desde el navegador con Swagger: <http://localhost:8080/swagger-ui.html>. El contrato en JSON está en <http://localhost:8080/v3/api-docs>. Por qué lo incluí y qué cambiaría para producción se explica en las secciones 2 y 4.

## 1. Supuestos y decisiones de comportamiento

### Reservas

- **Un reintento del mismo pedido devuelve la reserva existente.** Si llega nuevamente el mismo `orderId` con el mismo producto y la misma cantidad, no vuelvo a descontar stock ni extiendo el tiempo de la reserva. La razón es que la aplicación puede reenviar una solicitud cuando la conexión es lenta y ese reintento no debería convertirse en una segunda reserva.

  Esto está cubierto por `DefaultInventoryServiceTest.retryReturnsTheSameReservationWithoutDiscountingTwice` y `ConcurrencyTest.aHundredRetriesOfTheSameOrderReserveOnlyOnce`.

- **Si el mismo pedido llega con datos diferentes, lo rechazo.** Un `orderId` no puede aparecer primero con un producto y después con otro, ni con una cantidad diferente. En ese caso lanzo `IllegalArgumentException`. Devolver silenciosamente la reserva anterior ocultaría un error del cliente.

  Lo cubren `DefaultInventoryServiceTest.retryWithAnotherQuantityFails` y `theSameOrderCannotReserveAnotherProduct`.

- **Si la reserva ya venció, un nuevo intento comienza de nuevo.** Una vez terminado el plazo, considero que el cliente puede volver a intentar la compra. El pedido se trata entonces como una nueva solicitud.

  Se comprueba en `DefaultInventoryServiceTest.anOrderCanReserveAgainAfterItsReservationExpired`.

- **Un pedido queda asociado a un solo producto cuando consigue reservarlo.** Un intento que falla, por ejemplo por falta de stock, no deja el pedido asociado permanentemente a ese producto. Esto sigue la idea del enunciado de que cada producto del carrito se maneja como un pedido independiente.

  Se comprueba en `DefaultInventoryServiceTest.aFailedReservationDoesNotPinTheOrderToTheProduct`.

- **Un producto inexistente se comporta como si no tuviera stock.** `reserve` lanza `InsufficientStockException` y `available` devuelve `0`. Mantengo este comportamiento porque forma parte del contrato.

  Se comprueba en `DefaultInventoryServiceTest.reservingAnUnknownProductFailsAsInsufficientStock`.

- **Las validaciones de una reserva tienen un orden definido.** Primero valido la cantidad, después el límite de la categoría y finalmente el stock disponible. El límite depende de lo que solicita el pedido, no de las unidades que haya disponibles.

  Esto está cubierto por `DefaultInventoryServiceTest.theLimitIsCheckedBeforeTheStock`.

### Tiempo y disponibilidad

- **Las reservas vencen de forma natural, sin un proceso de limpieza en segundo plano.** En lugar de tener un hilo dedicado a buscar reservas vencidas, cada operación compara la hora de vencimiento con la hora actual cuando necesita conocer el estado real. Esto evita mantener un estado adicional que podría quedar desactualizado y permite probar el comportamiento con un reloj controlado.

  Se comprueba en `ReservationExpirationTest`.

- **Una reserva vence exactamente en su hora de expiración.** No considero que siga activa durante ese instante. Así queda definido también el comportamiento del caso límite.

  Lo cubren `ReservationRecordTest.expiredExactlyAtExpiry` y `ReservationExpirationTest`.

- **La disponibilidad representa el stock que todavía puede venderse.** Es el stock total menos las unidades que están reservadas y todavía esperan confirmación de pago. Las unidades ya confirmadas se consideran vendidas y no vuelven a estar disponibles.

  Esto se comprueba en `InventoryServiceTest.confirmedUnitsStaySold`.

- **La hora se obtiene de un reloj proporcionado al servicio y se consulta con el producto ya bloqueado.** De esta manera, un hilo que tuvo que esperar para acceder al producto no trabaja con una hora que quedó desactualizada mientras esperaba. Las pruebas de tiempo utilizan `MutableClock`.

### Confirmaciones

- **Confirmar dos veces el mismo pedido es seguro.** La segunda confirmación no hace nada. En cambio, confirmar una reserva que ya venció provoca `IllegalStateException`. Una vez vencida, esas unidades pudieron quedar disponibles para otro cliente y no sería seguro venderlas nuevamente.

  Este comportamiento está cubierto por `DefaultInventoryServiceTest.confirmingAnExpiredReservationFailsAndSellsNothing`.

- **Una unidad confirmada no vuelve al stock por el paso del tiempo.** La confirmación representa una venta, por lo que la unidad permanece vendida.

  Se comprueba en `ReservationExpirationTest`, en el caso correspondiente a una reserva confirmada.

### Productos y categorías

- **Registrar dos veces el mismo producto depende de la categoría.** Si se registra con la misma categoría, no hay problema. Si se intenta registrar con otra, lanzo `IllegalArgumentException`. Cambiar la categoría silenciosamente podría modificar reglas como el plazo de reserva o el límite de unidades de un producto que ya tiene reservas activas. Un SKU vacío también se rechaza.

  Se comprueba en `DefaultInventoryServiceTest.registeringWithAnotherCategoryFails`.

- **Las reglas de las categorías se mantienen como configuración y no dentro de la lógica.** Están en `category-policies.properties`. Esto permite agregar categorías sin tener que modificar la lógica de reservas cada vez que aparece una nueva regla de negocio.

  Se comprueba en `CategoryPoliciesTest.everyCategoryHasAPolicy`.

- **El límite de 2 unidades de `FLASH_SALE` se aplica por pedido, no por cliente.** El contrato no proporciona ninguna identidad del cliente, por lo que el servicio no tiene información suficiente para aplicar un límite por persona. El riesgo de negocio queda explicado más adelante.

  Se comprueba en `DefaultInventoryServiceTest.flashSaleRejectsMoreThanTwoUnitsPerOrder`.

### Avisos de bajo stock

- **El aviso se dispara cuando quedan 5 unidades o menos.** El valor no está fijado en código: puede cambiarse mediante `low-stock-threshold`.

  Se comprueba en `LowStockNotifierTest.alertsExactlyAtThreshold` y `invalidThresholdFailsAtStartup`.

- **Solo una reserva puede provocar un aviso de bajo stock.** `addStock` no genera avisos. Si un producto acaba de ser cargado con pocas unidades, no significa que se esté agotando; simplemente tiene poco stock desde el inicio.

  Se comprueba en `DefaultInventoryServiceTest.addingStockNeverAlerts`.

- **No repito el mismo aviso mientras el producto siga por debajo del umbral.** Si llegan varias reservas seguidas y todas mantienen el producto en estado de bajo stock, solo se genera un aviso.

  Esto está cubierto por `ConcurrencyTest.crossingTheThresholdWithManyThreadsAlertsExactlyOnce`.

- **El aviso vuelve a habilitarse únicamente cuando el producto se recupera realmente.** Para considerar que el producto salió del estado de bajo stock, sus unidades disponibles deben superar el umbral. Esto puede ocurrir por un reabastecimiento o porque vencen reservas. Si se repone una unidad y el producto queda exactamente en 5, no considero que se haya recuperado y, por tanto, no genero otro aviso inmediatamente.

  Se comprueba en `LowStockNotifierTest.restockThatStaysAtOrBelowThresholdDoesNotRearm`.

- **El vencimiento de una reserva no genera un aviso por sí mismo.** Como las reservas vencen de manera natural, el cambio se refleja en la siguiente operación que consulte o modifique ese producto.

  Se comprueba en `LowStockNotifierTest.expiryRearmsAlertOnTheNextOperation`.

- **Un fallo de un canal de aviso no debe deshacer una reserva.** Si un listener falla, el error se registra en el log y los demás canales siguen recibiendo el aviso. La reserva ya fue realizada y no tendría sentido revertirla porque falló una notificación.

  Se comprueba en `DefaultInventoryServiceTest.aFailingListenerDoesNotBreakTheReservation`.

### Otros detalles importantes

- **La configuración se incluye dentro del JAR.** Las reglas de las categorías y el umbral de bajo stock viven en archivos `.properties`. Si un valor es inválido o una categoría no tiene una regla, `Inventory.create` falla al arrancar, en lugar de descubrir el problema cuando ya hay clientes usando el servicio. La contrapartida es que cambiar estos valores requiere volver a construir el proyecto.

- **Agregar demasiado stock produce un error controlado.** `addStock` utiliza `Math.addExact`, de modo que superar el máximo representable por un entero provoca `ArithmeticException` en lugar de producir silenciosamente un valor incorrecto.

- **El uso de memoria crece con el tiempo.** Las reservas confirmadas y la relación entre cada `orderId` y su producto se conservan para evitar duplicar una venta ante un reintento. Las reservas vencidas se eliminan cuando se vuelve a operar sobre ese producto.

> **Un riesgo de negocio que el contrato no resuelve:** el límite de `FLASH_SALE` es por pedido. Un mismo cliente podría enviar varios pedidos para el mismo producto y superar las 2 unidades. Si el negocio necesita un límite por cliente, el contrato tendría que proporcionar la identidad del cliente.

## 2. Cómo diseñé el servicio y por qué

En esta parte explico las decisiones de diseño que considero más importantes y las alternativas que decidí no utilizar.

- **Las reglas se manejan como datos y no como código.** Cada categoría define su plazo y su límite en un archivo de propiedades. Así, agregar una nueva categoría no obliga a modificar un `switch` dentro de la lógica de reservas.

- **El repositorio controla el acceso al stock mediante `withProduct(sku, acción)`.** No devuelvo directamente el objeto que representa el stock para que otra parte de la aplicación tenga que bloquearlo por su cuenta. Centralizar el acceso evita que alguien utilice el producto sin protección y, además, deja una estructura que se puede traducir directamente a un `SELECT … FOR UPDATE` cuando el repositorio pase a una base de datos.

- **El bloqueo es por producto, no global.** Dos clientes que compran productos diferentes no deberían bloquearse entre sí. Esto permite que la concurrencia ocurra donde realmente es segura.

  Se comprueba en `ConcurrencyTest.manyProductsInParallelEachSellExactlyTheirOwnStock`.

- **El aviso se calcula con el producto bloqueado, pero se envía después de liberar el bloqueo.** De esta manera, un listener lento —por ejemplo, uno que tenga que comunicarse con otro sistema— no mantiene bloqueadas las ventas de ese producto.

  Se comprueba en `ConcurrencyTest.aSlowAlertListenerDoesNotKeepTheProductLocked`.

- **La marca de “ya avisé” forma parte del estado del stock.** No la guardo únicamente en memoria dentro del notificador. Si hubiera varias instancias del servicio, cada una tendría su propia marca y podrían generarse avisos duplicados.

- **`CompositeStockAlertListener` se encarga de distribuir los avisos y aislar los fallos.** Si un canal deja de funcionar, los demás siguen recibiendo el evento. Además, incorporar un canal nuevo no obliga a modificar el servicio de inventario.

- **Las reservas vencen comparando fechas, sin un hilo dedicado a liberarlas.** Esta solución es más sencilla, evita estados desactualizados y se puede probar fácilmente mediante un reloj controlado.

- **El envío de avisos es síncrono en esta versión.** Cuando `reserve` termina, los listeners ya han recibido el aviso. Para producción considero más apropiado utilizar una cola asíncrona, pero no era necesario introducir esa complejidad en esta entrega.

- **Toda la construcción del servicio está centralizada en `Inventory.create`.** No utilicé singletons ni estado compartido. Cada llamada crea un servicio independiente, lo que reduce el acoplamiento y facilita las pruebas.

- **Si el mismo pedido intenta reservar dos productos al mismo tiempo, solo uno puede quedar asociado.** Uno de los intentos gana y el otro libera su reserva mediante `ProductStock.release`. No asocié el pedido al producto antes de reservar porque un intento fallido, como una venta flash sin stock, podría dejar el pedido asociado de forma permanente a un producto que nunca llegó a reservarse.

  Se comprueba en `ConcurrencyTest.theSameOrderOnTwoProductsAtOnceLeavesExactlyOneReservation`.

- **La capa REST se mantiene deliberadamente fina.** Está en `com.store.inventory.web` y utiliza Spring Boot. Su responsabilidad es validar que lleguen los datos necesarios y delegar el trabajo en `InventoryService`. Las reglas de negocio siguen concentradas en un único lugar y pueden probarse sin necesidad de levantar HTTP.

  Se comprueba en `InventoryApiTest`.

- **La API se documenta con Swagger (springdoc-openapi), agregado como ajuste posterior a la entrega inicial.** Es la única dependencia que agregué además de Spring Boot. Los endpoints se descubren solos a partir de `InventoryController`, y las descripciones y códigos de error están en anotaciones sobre el controlador y los modelos; no hay un archivo de documentación aparte que se pueda desactualizar. La interfaz queda en `/swagger-ui.html` y el contrato en `/v3/api-docs`. Esto no toca el contrato original ni las reglas de negocio. Para producción conviene desactivarla o protegerla (ver la sección 4).

  Se comprueba en `OpenApiDocsTest`.

- **`ApiExceptionHandler` convierte los errores del contrato en respuestas HTTP.** `IllegalArgumentException` se convierte en 400, `InsufficientStockException` en 409, `OrderLimitExceededException` en 422 e `IllegalStateException` en 409. No añadí nuevas excepciones porque el contrato no se puede modificar y el servicio utiliza algunas excepciones para diferentes situaciones; el código y el mensaje de error permiten distinguir el caso concreto.

- **El aviso de la aplicación web solo escribe en el log.** `LoggingStockAlertListener` representa el canal disponible en esta entrega. No implementé el envío real de correos porque ese canal debe ser proporcionado por el equipo y puede incorporarse mediante `CompositeStockAlertListener.of(...)` sin modificar el servicio.

- **Spring Boot se incorpora mediante su BOM y no heredando su proyecto padre.** De esta forma, el `pom.xml` continúa perteneciendo al proyecto. Durante las pruebas apareció un detalle relacionado con los nombres de los parámetros: el compilador no los estaba conservando. Lo resolví habilitando `maven.compiler.parameters` y utilizando nombres explícitos en `@PathVariable`.

## 3. Lo que decidí dejar fuera

Estas cosas no se quedaron fuera por olvido. Las revisé y decidí no incluirlas porque no forman parte del contrato o porque añadirlas en esta etapa habría introducido complejidad innecesaria.

- **Persistencia en una base de datos.** La implementación actual utiliza `InMemoryInventoryRepository`. La interfaz `InventoryRepository` deja preparada la sustitución por otra implementación.

- **Coordinación entre varias instancias del servicio.** El bloqueo actual protege la concurrencia dentro de una misma ejecución de la aplicación.

- **Envío asíncrono y reintento de avisos.**

- **Métricas, trazas y paneles de operación.**

- **Cancelación de reservas.** El contrato no contempla esta operación. Si el cliente no confirma el pago, la reserva simplemente vence.

- **Confirmaciones parciales, ampliación del plazo, ajuste o retiro de stock.**

- **Identificación y autorización de clientes.** La API REST actual no implementa autenticación.

- **Un canal real de notificaciones.** No se implementó correo, SMS ni chat; en esta versión el aviso se escribe en el log.

- **Versionado y paginación de la API.** La documentación OpenAPI sí está incluida (ver la sección 2), pero la API no tiene versión en la ruta ni listados que paginar.

- **Cambio de configuración sin reiniciar la aplicación.**

## 4. Qué cambiaría antes de llevarlo a producción

Si este servicio fuera a producción, estas serían mis prioridades.

### Lo más urgente

- **Pasaría el inventario a una base de datos con bloqueo a nivel de fila.** Utilizaría, por ejemplo, `SELECT … FOR UPDATE`, o un mecanismo de bloqueo optimista con reintentos. Sin una protección de este tipo, dos instancias distintas podrían terminar vendiendo las mismas unidades.

- **Añadiría una restricción única sobre `order_id`.** La protección contra reintentos no debería depender únicamente de la memoria de una instancia. La restricción debe sobrevivir a los reinicios y funcionar cuando haya varias instancias.

- **Persistiría los eventos de aviso junto con el cambio de stock y los entregaría después mediante un mecanismo de reintentos.** Un patrón como *outbox* permitiría evitar la pérdida de avisos cuando un canal está temporalmente caído y ayudaría a conservar el orden de los eventos por producto. En la implementación actual, un aviso se entrega como máximo una vez: si el canal falla, se pierde, y dos avisos del mismo producto podrían llegar en un orden diferente cuando se ejecutan desde hilos distintos.

- **Tomaría la hora desde la base de datos.** Así evitaría depender de que los relojes de varias instancias estén perfectamente sincronizados.

- **Añadiría autenticación y autorización a la API REST.** Actualmente, cualquiera que tenga acceso a la red podría intentar reservar o agregar stock.

### Importante

- **Apagaría o protegería Swagger fuera de desarrollo.** Hoy `/swagger-ui.html` y `/v3/api-docs` están abiertos. En producción los desactivaría con `springdoc.swagger-ui.enabled=false` y `springdoc.api-docs.enabled=false`, o los dejaría detrás de la misma autenticación que el resto de la API.

- **Mantendría un contador de unidades apartadas.** Actualmente `ProductStock.availableAt` recorre las reservas del producto cada vez que necesita calcular la disponibilidad. Con miles de reservas sobre un producto muy demandado, este enfoque podría convertirse en un cuello de botella.

- **Revisaría cómo manejar un producto especialmente concurrido durante una venta flash.** Todas las solicitudes terminan compitiendo por el mismo registro. Dependiendo del volumen, podría ser necesario estudiar contadores distribuidos o algún mecanismo de descuento atómico.

- **Limpiaría y archivaría las reservas vencidas y confirmadas.** En la implementación actual, parte de la información permanece en memoria y esta crecería de forma indefinida.

- **Añadiría métricas útiles para operar el servicio.** Como mínimo mediría reservas por segundo, fallos por falta de stock, tiempo de espera por bloqueos y avisos fallidos. También incluiría el `orderId` en los logs. Sin esta información sería difícil entender qué está ocurriendo durante una venta flash real.

- **Permitiría cambiar la configuración sin tener que reconstruir la aplicación.**

- **Introduciría excepciones específicas para distinguir mejor los errores.** Por ejemplo, podría utilizar 409 para conflictos y 404 para pedidos inexistentes. Actualmente `IllegalArgumentException` termina como 400 e `IllegalStateException` como 409, por lo que un error de programación podría terminar pareciendo un error provocado por el cliente.

### Cuando haya tiempo

- **Limitaría la cantidad máxima permitida por solicitud.** Es una defensa sencilla frente a cantidades absurdamente grandes.

- **Haría pruebas de carga y pruebas contra la base de datos real.** Las pruebas actuales cubren muy bien el comportamiento del servicio, pero no demuestran cómo se comportaría con varias instancias ni cuál sería su rendimiento bajo una carga real.

## 5. Cómo comprobé que funciona

No me limité a comprobar los casos felices. Las pruebas buscan cubrir también los bordes y, sobre todo, los problemas que pueden aparecer cuando varias operaciones ocurren al mismo tiempo.

- **Pruebas unitarias por capa.** Hay pruebas para las políticas, el modelo de dominio, el repositorio y el sistema de notificaciones.

- **Pruebas del servicio utilizando un reloj controlado.** `MutableClock` permite comprobar los vencimientos sin tener que esperar realmente. También se prueban los límites exactos de expiración de cada categoría.

- **Pruebas de concurrencia.** Hay escenarios con muchos hilos ejecutándose al mismo tiempo y que se repiten varias veces: 200 clientes compitiendo por 50 unidades, 100 reintentos del mismo pedido, múltiples hilos cruzando el umbral de bajo stock, 20 productos operando en paralelo, confirmaciones y reservas simultáneas y un listener de avisos lento.

- **También introduje fallos de forma intencional.** Quité el bloqueo, desactivé la protección contra reintentos, envié el aviso mientras el producto seguía bloqueado y eliminé la compensación de una reserva. En cada caso, la prueba correspondiente detectó el problema. Esto me permitió comprobar que las pruebas no solo cubren el comportamiento correcto, sino que realmente detectan las regresiones que quería evitar.

- **Pruebas de la API REST.** `InventoryApiTest` simula las peticiones HTTP utilizando un reloj controlable y comprueba códigos HTTP, cuerpos de error, reintentos y vencimientos.

- **Pruebas de la documentación Swagger.** `OpenApiDocsTest` comprueba que `/v3/api-docs` lista los cinco endpoints, que `POST /api/reservations` documenta los códigos 201, 400, 409 y 422, que cada petición de registro y de reserva trae sus ejemplos por categoría, que la descripción incluye la guía para validar, y que la página de Swagger UI se sirve. Si alguien cambia una ruta del controlador o quita la dependencia, este test avisa.

- **Prueba de la API con Postman.** La colección está en el repositorio (`inventory-reservations-postman.json`). Se ejecutó con Newman contra la aplicación levantada: 54 peticiones y 159 aserciones, todas correctas, y se puede repetir sin reiniciar la aplicación. Cubre el plazo y el límite de las tres categorías, y una carpeta que lleva un producto por el umbral de bajo stock: el aviso no se puede leer por la API, así que esa parte se confirma mirando el log (deben aparecer exactamente dos avisos por ejecución). La expiración real de cinco minutos se probó por separado porque depende del reloj real; el comportamiento exacto de la expiración queda cubierto por las pruebas de Java.

- **Los tres tests originales de `InventoryServiceTest` siguen pasando sin modificaciones.** Tampoco modifiqué ningún archivo dentro de `com.store.inventory.api`.

> **Qué no demuestran estas pruebas:** no pueden garantizar que no exista ninguna condición de carrera entre hilos; únicamente hacen muy poco probable que una carrera pase desapercibida. Tampoco demuestran cómo se comportaría el servicio con varias instancias ni cuál sería su rendimiento bajo una carga de producción.

## 6. Ajustes posteriores a la entrega inicial

### Documentación Swagger (OpenAPI)

Agregué Swagger después de terminar y probar la entrega inicial, para que quien use la API pueda ver y probar los endpoints sin leer el código ni importar una colección.

Qué cambió:

- **`pom.xml`:** una dependencia nueva, `springdoc-openapi-starter-webmvc-ui` (versión 2.6.0, guardada en la propiedad `springdoc.version`).
- **`OpenApiConfig` (archivo nuevo):** el título, la versión y la descripción general de la API.
- **`InventoryController` y `ApiModels`:** solo anotaciones (`@Operation`, `@ApiResponse`, `@Schema`, `@Parameter`, `@ExampleObject`) con descripciones, ejemplos y los códigos de error 400, 409 y 422. No se tocó la lógica.
- **Swagger pensado para validar:** cada registro y cada reserva trae un ejemplo por categoría, incluido el caso `FLASH_SALE` con 3 unidades (debe dar 422), y la descripción de la API trae una guía paso a paso para comprobar las reglas.
- **`OpenApiDocsTest` (archivo nuevo):** cinco pruebas que protegen la documentación, sus ejemplos y la guía.
- **Colección de Postman (`inventory-reservations-postman.json`):** las variables traen valores por defecto concretos (`STD-001`, `FLASH-001`, ...), y agregué lo que faltaba para validar el enunciado: la reserva `PRE_ORDER` con su plazo de 24 horas, y la carpeta `07 Alerta de bajo stock`. También corregí la ruta del archivo que mostraba su descripción, y agregué la tabla que relaciona cada requisito con la parte que lo valida. Pasó de 41 a 54 peticiones.
- **Este documento:** la intro y las secciones 2, 3, 4 y 5 mencionan Swagger.

Qué no cambió:

- El paquete `com.store.inventory.api` ni la firma de `Inventory.create`.
- Las reglas de negocio ni el comportamiento de ningún endpoint.
- Los tests existentes. Después del ajuste pasan 411 pruebas (las 406 anteriores más las 5 nuevas) y la colección de Postman pasa completa (54 peticiones, 159 aserciones).
