package io.seatstorm.contracts;

import java.time.Clock;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;

/** Factory and accessors for {@link EventMeta}. */
public final class EventMetas {
    private EventMetas() {}

    public static EventMeta of(String producer, String correlationId) {
        return of(producer, correlationId, null);
    }

    public static EventMeta of(String producer, String correlationId, String causationId) {
        return of(producer, correlationId, causationId, Clock.systemUTC());
    }

    public static EventMeta of(String producer, String correlationId, String causationId, Clock clock) {
        return EventMeta.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOccurredAtMs(clock.millis())
                .setCorrelationId(correlationId)
                .setCausationId(causationId)
                .setProducer(producer)
                .build();
    }

    /** The {@code meta} field of any top-level event, or null for records without one. */
    public static EventMeta metaOf(SpecificRecord event) {
        if (event instanceof EventMeta m) {
            return m;
        }
        var field = event.getSchema().getField("meta");
        return field == null ? null : (EventMeta) event.get(field.pos());
    }

    /** The eventId used for inbox de-duplication. Throws if the record has no meta. */
    public static String idOf(SpecificRecord event) {
        EventMeta m = metaOf(event);
        if (m == null) {
            throw new IllegalArgumentException("Record has no EventMeta: " + event.getClass().getSimpleName());
        }
        return m.getEventId();
    }

    public static String correlationIdOf(SpecificRecord event) {
        return metaOf(event).getCorrelationId();
    }
}
