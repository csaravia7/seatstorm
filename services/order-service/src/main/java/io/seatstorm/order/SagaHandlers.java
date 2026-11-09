package io.seatstorm.order;

import io.quarkus.logging.Log;
import io.seatstorm.contracts.*;
import io.seatstorm.platform.inbox.Inbox;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.avro.specific.SpecificRecord;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/** Kafka entry points. Inbox check and saga reaction share one transaction; a failure goes to the DLQ. */
@ApplicationScoped
public class SagaHandlers {
    @Inject
    Inbox inbox;

    @Inject
    OrderSaga saga;

    @Incoming("sale-events")
    @Blocking
    @Transactional
    public void onSale(Record<String, SpecificRecord> rec) {
        SpecificRecord v = rec.value();
        if (v == null || !inbox.firstTime("order-service.sale-events", EventMetas.idOf(v))) {
            return;
        }
        switch (v) {
            case SaleOpened e -> saga.on(e);
            case SaleClosed e -> saga.on(e);
            default -> Log.warnf("Ignoring %s", v.getClass().getSimpleName());
        }
    }

    @Incoming("seat-events")
    @Blocking
    @Transactional
    public void onSeat(Record<String, SpecificRecord> rec) {
        SpecificRecord v = rec.value();
        if (v == null || !inbox.firstTime("order-service.seat-events", EventMetas.idOf(v))) {
            return;
        }
        switch (v) {
            case SeatsHeld e -> saga.on(e);
            case SeatsRejected e -> saga.on(e);
            case SeatsHoldExtended e -> saga.on(e);
            case SeatsReleased e -> saga.on(e);
            case SeatsSold e -> saga.on(e);
            case SeatsConfirmFailed e -> saga.on(e);
            default -> Log.warnf("Ignoring %s", v.getClass().getSimpleName());
        }
    }

    @Incoming("payment-events")
    @Blocking
    @Transactional
    public void onPayment(Record<String, SpecificRecord> rec) {
        SpecificRecord v = rec.value();
        if (v == null || !inbox.firstTime("order-service.payment-events", EventMetas.idOf(v))) {
            return;
        }
        switch (v) {
            case PaymentAuthorized e -> saga.on(e);
            case PaymentFailed e -> saga.on(e);
            case PaymentRefunded e -> saga.on(e);
            default -> Log.warnf("Ignoring %s", v.getClass().getSimpleName());
        }
    }
}
