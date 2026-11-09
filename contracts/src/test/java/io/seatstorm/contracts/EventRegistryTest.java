package io.seatstorm.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

class EventRegistryTest {

    @Test
    void registryCoversEveryRecordOfTheProtocol() {
        var types = SeatStorm.PROTOCOL.getTypes();
        assertEquals(25, types.size());
        assertEquals(25, EventRegistry.all().size());
        for (Schema s : types) {
            assertTrue(EventRegistry.all().containsKey(s.getName()), s.getName());
            assertEquals(s, EventRegistry.schemaOf(s.getName()));
        }
    }

    @Test
    void roundTripForAllRecords() throws Exception {
        Random rnd = new Random(42);
        for (var entry : EventRegistry.all().entrySet()) {
            Schema schema = EventRegistry.schemaOf(entry.getKey());
            // Build a random instance generically, then express it as Avro JSON.
            Object generic = random(schema, rnd);
            String json = genericToJson(schema, generic);

            SpecificRecord obj = EventRegistry.decode(entry.getKey(), json);
            assertSame(entry.getValue(), obj.getClass());
            String again = EventRegistry.encode(obj);
            assertEquals(json, again, entry.getKey());
            assertEquals(obj, EventRegistry.decode(entry.getKey(), again));
            // nested records are real generated classes, not generic
            if (schema.getField("meta") != null) {
                assertInstanceOf(EventMeta.class, obj.get(schema.getField("meta").pos()));
            }
        }
    }

    @Test
    void metaAndNullableUnions() {
        var meta = EventMetas.of("order-service", "order-1");
        assertNull(meta.getCausationId());
        var ev = OrderStatusChanged.newBuilder().setMeta(meta).setOrderId("o1").setBuyerId("b1")
                .setStatus("HELD").setReason(null).setHeldUntilMs(123L).build();
        String json = EventRegistry.encode(ev);
        var back = (OrderStatusChanged) EventRegistry.decode("OrderStatusChanged", json);
        assertEquals(ev, back);
        assertEquals(meta.getEventId(), EventMetas.idOf(back));
        assertEquals("order-1", EventMetas.correlationIdOf(back));
    }

    @Test
    void unknownTypeFails() {
        assertThrows(IllegalArgumentException.class, () -> EventRegistry.decode("Nope", "{}"));
    }

    // ---- helpers

    private static String genericToJson(Schema schema, Object datum) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        var enc = EncoderFactory.get().jsonEncoder(schema, out);
        new GenericDatumWriter<Object>(schema).write(datum, enc);
        enc.flush();
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Object random(Schema s, Random r) {
        return switch (s.getType()) {
            case RECORD -> {
                var rec = new GenericData.Record(s);
                for (Schema.Field f : s.getFields()) {
                    rec.put(f.pos(), random(f.schema(), r));
                }
                yield rec;
            }
            case STRING -> "s" + r.nextInt(1000);
            case INT -> r.nextInt(100000);
            case LONG -> (long) r.nextInt(100000) * 1000L;
            case ARRAY -> {
                List<Object> l = new ArrayList<>();
                for (int i = 0; i < 1 + r.nextInt(3); i++) {
                    l.add(random(s.getElementType(), r));
                }
                yield new GenericData.Array<>(s, l);
            }
            case UNION -> random(s.getTypes().get(s.getTypes().size() - 1), r); // non-null branch
            default -> throw new IllegalStateException("Unsupported " + s.getType());
        };
    }
}
