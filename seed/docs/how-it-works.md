# Cómo funciona SeatStorm

SeatStorm es una demo de venta de entradas bajo mucha concurrencia: un solo concierto (Neon Tide, Arena Norte) con 12 secciones y 360 asientos. Nunca se vende un asiento dos veces.

## Flujo de una compra (saga)
1. El navegador envía la orden. El servicio de órdenes la registra como PLACED y responde enseguida (la respuesta es asíncrona).
2. El servicio de inventario intenta retener los asientos. Si lo logra, la orden pasa a HELD; si alguien se adelantó, pasa a REJECTED con motivo SEAT_TAKEN.
3. Al pagar (PAYING), el simulador de pagos aprueba o rechaza. Si aprueba, la orden pasa a CONFIRMING.
4. El inventario marca los asientos como vendidos (SOLD) y la orden queda CONFIRMED. Se emite el ticket con código QR.
5. Si algo falla en el camino, la saga compensa: libera asientos o reembolsa el pago.

Estados finales de una orden: CONFIRMED, REJECTED, CANCELLED y EXPIRED.

## Por qué no hay sobreventa
Hay tres capas de protección. Primero, los mensajes de un mismo asiento van siempre a la misma partición de Kafka, así que se procesan en orden. Segundo, la base de datos del inventario actualiza el asiento solo si sigue disponible, de forma atómica. Tercero, una restricción única impide dos órdenes activas por persona y cada mensaje se procesa una sola vez (inbox).

## Piezas principales
- Inventario de asientos: la fuente de verdad de cada asiento.
- Servicio de órdenes: dirige la saga.
- Simulador de pagos: puede ir bien, lento, fallar al azar o pausarse.
- Consulta del mapa: una copia de lectura optimizada que alimenta el mapa, las estadísticas y la línea de tiempo de cada orden. Puede ir unos milisegundos por detrás.
- Notificaciones y tickets: avisan a tu pantalla en vivo.
- Kafka conecta todo con eventos; cada servicio tiene su propia base de datos.

## Ejemplo sencillo
Es como un guardarropa con un solo ayudante por fila de perchas: aunque llegue una multitud, cada percha se entrega en orden de llegada y nunca a dos personas.

## Línea de tiempo de una orden
Cada paso (orden creada, asientos retenidos, pago autorizado, ticket emitido) queda registrado. Puedo leerla para explicarte qué pasó con tu orden.
