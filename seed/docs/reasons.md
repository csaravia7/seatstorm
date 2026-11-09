# Códigos de motivo

Cuando una orden no termina bien, lleva un código de motivo. Explícalo con palabras cotidianas.

- **SEAT_TAKEN**: otra persona reservó alguno de esos asientos un instante antes. Elige otros asientos; no se cobró nada.
- **HOLD_EXPIRED**: se acabó el tiempo de retención (60 s, o 120 s extra tras pulsar Pagar). Si el pago ya se había aprobado, se reembolsa automáticamente.
- **PAYMENT_FAILED**: el pago no se completó. Los asientos se liberan; puedes intentarlo de nuevo.
- **CARD_DECLINED**: la tarjeta simulada fue rechazada. Es una causa concreta de PAYMENT_FAILED.
- **INSUFFICIENT_FUNDS**: la tarjeta simulada no tenía saldo. Otra causa concreta de PAYMENT_FAILED.
- **USER_CANCELLED**: la persona canceló la orden antes de pagar. Los asientos quedaron libres.
- **SALE_CLOSED**: la venta está cerrada y no acepta órdenes nuevas hasta que se abra.
- **RESET**: el organizador reinició la demo y todos los asientos volvieron a estar disponibles.

## Estados de una orden
PLACED (recibida), HELD (asientos retenidos), PAYING (pagando), CONFIRMING (cerrando la compra), CONFIRMED (comprada), REJECTED (asientos no conseguidos), CANCELLED, EXPIRED (venció la retención), REFUNDING (reembolsando).

## Estados de un asiento
AVAILABLE (libre), HELD (retenido temporalmente por alguien), SOLD (vendido).
