package io.seatstorm.query;

import io.quarkus.logging.Log;
import io.seatstorm.contracts.EventMetas;
import io.seatstorm.platform.inbox.Inbox;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.avro.specific.SpecificRecord;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/** Kafka entry points of the read model. Inbox check and projection share one transaction. */
@ApplicationScoped
public class EventConsumers {

    @Inject
    Inbox inbox;

    @Inject
    Projector projector;

    @Incoming("seat-events")
    @Blocking
    @Transactional
    public void onSeat(Record<String, SpecificRecord> rec) {
        if (first("seat-events", rec.value())) {
            projector.onSeatEvent(rec.value());
        }
    }

    @Incoming("order-events")
    @Blocking
    @Transactional
    public void onOrder(Record<String, SpecificRecord> rec) {
        if (first("order-events", rec.value())) {
            projector.onOrderEvent(rec.value());
        }
    }

    @Incoming("ticket-events")
    @Blocking
    @Transactional
    public void onTicket(Record<String, SpecificRecord> rec) {
        if (first("ticket-events", rec.value())) {
            projector.onTicketEvent(rec.value());
        }
    }

    @Incoming("sale-events")
    @Blocking
    @Transactional
    public void onSale(Record<String, SpecificRecord> rec) {
        if (first("sale-events", rec.value())) {
            projector.onSaleEvent(rec.value());
        }
    }

    private boolean first(String channel, SpecificRecord v) {
        if (v == null) {
            Log.warnf("Null value on %s ignored", channel);
            return false;
        }
        return inbox.firstTime("seatmap-query." + channel, EventMetas.idOf(v));
    }
}
