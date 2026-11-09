package io.seatstorm.ticketing;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.avro.specific.SpecificRecord;
import org.eclipse.microprofile.reactive.messaging.Incoming;

@ApplicationScoped
public class OrderEventsHandler {
    @Inject
    TicketService tickets;

    @Incoming("order-events")
    @Blocking
    public void on(Record<String, SpecificRecord> rec) {
        if (rec.value() != null) {
            tickets.handle(rec.value());
        }
    }
}
