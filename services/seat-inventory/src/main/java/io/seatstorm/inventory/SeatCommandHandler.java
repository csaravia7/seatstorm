package io.seatstorm.inventory;

import io.quarkus.logging.Log;
import io.seatstorm.contracts.ConfirmSeats;
import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.ExtendHold;
import io.seatstorm.contracts.HoldSeats;
import io.seatstorm.contracts.ReleaseSeats;
import io.seatstorm.platform.inbox.Inbox;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.avro.specific.SpecificRecord;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * seat.commands consumer. Ordered per partition (blocking, one record at a time), one transaction per command
 * together with the inbox row. Any exception rolls back (inbox included) and the message goes to seat.commands.dlq.
 */
@ApplicationScoped
public class SeatCommandHandler {

    public static final String CONSUMER = "seat-inventory.seat-commands";

    @Inject
    Inbox inbox;

    @Inject
    SeatService seats;

    @Incoming("seat-commands")
    @Blocking
    @Transactional
    public void on(Record<String, SpecificRecord> rec) {
        SpecificRecord v = rec.value();
        if (v == null) {
            throw new IllegalArgumentException("Null seat command");
        }
        if (!inbox.firstTime(CONSUMER, EventMetas.idOf(v))) {
            Log.debugf("Duplicate %s ignored", v.getClass().getSimpleName());
            return;
        }
        switch (v) {
            case HoldSeats c -> seats.hold(c);
            case ExtendHold c -> seats.extend(c);
            case ConfirmSeats c -> seats.confirm(c);
            case ReleaseSeats c -> seats.release(c);
            default -> Log.warnf("Ignoring %s", v.getClass().getSimpleName());
        }
    }
}
