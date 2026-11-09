package io.seatstorm.contracts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.JsonEncoder;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.avro.specific.SpecificRecord;

/**
 * Static map {@code simpleName -> Avro class} for all 25 records of the IDL (23 events plus
 * {@code EventMeta} and {@code SeatChange}). No {@code Class.forName}: native-image friendly.
 * JSON means the standard Avro JSON encoding (unions are wrapped, e.g. {@code {"string":"x"}}).
 */
public final class EventRegistry {

    private static final Map<String, Class<? extends SpecificRecord>> CLASSES;
    private static final Map<String, Schema> SCHEMAS;
    private static final Map<String, Class<? extends SpecificRecord>> BY_FULL_NAME;
    private static final SpecificData DATA;

    static {
        Map<String, Class<? extends SpecificRecord>> c = new LinkedHashMap<>();
        Map<String, Schema> s = new LinkedHashMap<>();
        Map<String, Class<? extends SpecificRecord>> f = new LinkedHashMap<>();
        reg(c, s, f, EventMeta.class, EventMeta.getClassSchema());
        reg(c, s, f, SeatChange.class, SeatChange.getClassSchema());
        reg(c, s, f, SaleOpened.class, SaleOpened.getClassSchema());
        reg(c, s, f, SaleClosed.class, SaleClosed.getClassSchema());
        reg(c, s, f, HoldSeats.class, HoldSeats.getClassSchema());
        reg(c, s, f, ExtendHold.class, ExtendHold.getClassSchema());
        reg(c, s, f, ConfirmSeats.class, ConfirmSeats.getClassSchema());
        reg(c, s, f, ReleaseSeats.class, ReleaseSeats.getClassSchema());
        reg(c, s, f, SeatsHeld.class, SeatsHeld.getClassSchema());
        reg(c, s, f, SeatsRejected.class, SeatsRejected.getClassSchema());
        reg(c, s, f, SeatsHoldExtended.class, SeatsHoldExtended.getClassSchema());
        reg(c, s, f, SeatsSold.class, SeatsSold.getClassSchema());
        reg(c, s, f, SeatsConfirmFailed.class, SeatsConfirmFailed.getClassSchema());
        reg(c, s, f, SeatsReleased.class, SeatsReleased.getClassSchema());
        reg(c, s, f, OrderPlaced.class, OrderPlaced.getClassSchema());
        reg(c, s, f, OrderStatusChanged.class, OrderStatusChanged.getClassSchema());
        reg(c, s, f, OrderConfirmed.class, OrderConfirmed.getClassSchema());
        reg(c, s, f, RequestPayment.class, RequestPayment.getClassSchema());
        reg(c, s, f, PaymentAuthorized.class, PaymentAuthorized.getClassSchema());
        reg(c, s, f, PaymentFailed.class, PaymentFailed.getClassSchema());
        reg(c, s, f, RefundPayment.class, RefundPayment.getClassSchema());
        reg(c, s, f, PaymentRefunded.class, PaymentRefunded.getClassSchema());
        reg(c, s, f, TicketIssued.class, TicketIssued.getClassSchema());
        reg(c, s, f, ResetRequested.class, ResetRequested.getClassSchema());
        reg(c, s, f, ResetCompleted.class, ResetCompleted.getClassSchema());
        CLASSES = Collections.unmodifiableMap(c);
        SCHEMAS = Collections.unmodifiableMap(s);
        BY_FULL_NAME = Collections.unmodifiableMap(f);
        // Resolve record classes from the static map instead of Class.forName.
        DATA = new SpecificData() {
            @Override
            public Class<?> getClass(Schema schema) {
                if (schema.getType() == Schema.Type.RECORD) {
                    Class<?> k = BY_FULL_NAME.get(schema.getFullName());
                    if (k != null) {
                        return k;
                    }
                }
                return super.getClass(schema);
            }
        };
    }

    private static void reg(Map<String, Class<? extends SpecificRecord>> c, Map<String, Schema> s,
                            Map<String, Class<? extends SpecificRecord>> f,
                            Class<? extends SpecificRecord> k, Schema schema) {
        c.put(k.getSimpleName(), k);
        s.put(k.getSimpleName(), schema);
        f.put(schema.getFullName(), k);
    }

    public EventRegistry() {}

    /** Immutable {@code simpleName -> class} view of all registered records. */
    public static Map<String, Class<? extends SpecificRecord>> all() {
        return CLASSES;
    }

    public static Class<? extends SpecificRecord> classOf(String type) {
        Class<? extends SpecificRecord> k = CLASSES.get(type);
        if (k == null) {
            throw new IllegalArgumentException("Unknown event type: " + type);
        }
        return k;
    }

    public static Schema schemaOf(String type) {
        classOf(type);
        return SCHEMAS.get(type);
    }

    /** Rebuilds an event from its Avro JSON encoding. */
    public static SpecificRecord decode(String type, String json) {
        Schema schema = schemaOf(type);
        try {
            var reader = new SpecificDatumReader<SpecificRecord>(schema, schema, DATA);
            return reader.read(null, DecoderFactory.get().jsonDecoder(schema, json));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot decode " + type, e);
        }
    }

    /** Avro JSON encoding of an event. */
    public static String encode(SpecificRecord event) {
        Schema schema = event.getSchema();
        try {
            var out = new ByteArrayOutputStream();
            JsonEncoder enc = EncoderFactory.get().jsonEncoder(schema, out);
            new SpecificDatumWriter<SpecificRecord>(schema, DATA).write(event, enc);
            enc.flush();
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot encode " + event.getClass().getSimpleName(), e);
        }
    }

    // Instance API (injectable via platform-lib producer); delegates to the static one.

    public SpecificRecord fromJson(String type, String json) {
        return decode(type, json);
    }

    public String toJson(SpecificRecord event) {
        return encode(event);
    }

    public String typeOf(SpecificRecord event) {
        return event.getClass().getSimpleName();
    }
}
