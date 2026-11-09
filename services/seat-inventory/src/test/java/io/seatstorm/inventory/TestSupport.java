package io.seatstorm.inventory;

import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.seatstorm.contracts.ConfirmSeats;
import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.EventRegistry;
import io.seatstorm.contracts.ExtendHold;
import io.seatstorm.contracts.HoldSeats;
import io.seatstorm.contracts.ReleaseSeats;
import io.seatstorm.contracts.VenueLayout;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.microprofile.config.ConfigProvider;

@ApplicationScoped
public class TestSupport {
    public static final String CONCERT = VenueLayout.CONCERT_ID;

    @Inject
    EntityManager em;

    @Inject
    EventRegistry registry;

    public static String bootstrap() {
        return ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String.class);
    }

    public static String registryUrl() {
        return ConfigProvider.getConfig()
                .getValue("mp.messaging.connector.smallrye-kafka.apicurio.registry.url", String.class);
    }

    public static List<ConsumerRecord<String, SpecificRecord>> consume(KafkaCompanion kc, String topic, int n) {
        var task = kc.consumeWithDeserializers(
                        "org.apache.kafka.common.serialization.StringDeserializer",
                        "org.apache.kafka.common.serialization.ByteArrayDeserializer")
                .withProp("auto.offset.reset", "earliest")
                .withGroupId("test-" + topic + "-" + System.nanoTime())
                .fromTopics(topic, n);
        task.awaitCompletion(Duration.ofSeconds(90));
        var de = new AvroKafkaDeserializer<SpecificRecord>();
        de.configure(Map.of("apicurio.registry.url", registryUrl(), "apicurio.registry.use-specific-avro-reader", "true"),
                false);
        List<ConsumerRecord<String, SpecificRecord>> out = new ArrayList<>();
        for (Object o : task.getRecords()) {
            @SuppressWarnings("unchecked")
            var r = (ConsumerRecord<String, byte[]>) o;
            out.add(new ConsumerRecord<>(r.topic(), r.partition(), r.offset(), r.key(), de.deserialize(r.topic(), r.value())));
        }
        return out;
    }

    /** Everything on a topic from the beginning, reading for the given window. */
    public static List<ConsumerRecord<String, SpecificRecord>> consumeAll(KafkaCompanion kc, String topic, Duration window) {
        var task = kc.consumeWithDeserializers(
                        "org.apache.kafka.common.serialization.StringDeserializer",
                        "org.apache.kafka.common.serialization.ByteArrayDeserializer")
                .withProp("auto.offset.reset", "earliest")
                .withGroupId("test-all-" + topic + "-" + System.nanoTime())
                .fromTopics(topic, window);
        task.awaitCompletion(window.plusSeconds(30));
        var de = new AvroKafkaDeserializer<SpecificRecord>();
        de.configure(Map.of("apicurio.registry.url", registryUrl(), "apicurio.registry.use-specific-avro-reader", "true"),
                false);
        List<ConsumerRecord<String, SpecificRecord>> out = new ArrayList<>();
        for (Object o : task.getRecords()) {
            @SuppressWarnings("unchecked")
            var r = (ConsumerRecord<String, byte[]>) o;
            out.add(new ConsumerRecord<>(r.topic(), r.partition(), r.offset(), r.key(), de.deserialize(r.topic(), r.value())));
        }
        return out;
    }

    /** One long-lived Avro producer (explicit partition + key); reuse it, never create one per message. */
    public static class Sender {
        private final io.smallrye.reactive.messaging.kafka.companion.ProducerBuilder<Object, Object> producer;

        public Sender(KafkaCompanion kc) {
            this.producer = kc.produceWithSerializers("org.apache.kafka.common.serialization.StringSerializer",
                            "io.apicurio.registry.serde.avro.AvroKafkaSerializer")
                    .withProp("apicurio.registry.url", registryUrl())
                    .withProp("apicurio.registry.auto-register", "true")
                    .withProp("apicurio.registry.artifact-resolver-strategy",
                            "io.apicurio.registry.serde.avro.strategy.RecordIdStrategy")
                    .withProp("apicurio.registry.dereference-schema", "true");
        }

        public void send(String topic, String key, int partition, List<? extends SpecificRecord> events) {
            List<ProducerRecord<Object, Object>> recs = new ArrayList<>();
            for (SpecificRecord e : events) {
                recs.add(new ProducerRecord<>(topic, partition, key, e));
            }
            producer.fromRecords(recs).awaitCompletion(Duration.ofSeconds(60));
        }
    }

    public static void send(KafkaCompanion kc, String topic, String key, int partition, List<? extends SpecificRecord> events) {
        new Sender(kc).send(topic, key, partition, events);
    }

    public long count(String sql) {
        return QuarkusTransaction.requiringNew().call(
                () -> ((Number) em.createNativeQuery(sql).getSingleResult()).longValue());
    }

    public void exec(String sql) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(sql).executeUpdate());
    }

    @SuppressWarnings("unchecked")
    public List<Object[]> rows(String sql) {
        List<Object> raw = QuarkusTransaction.requiringNew().call(() -> (List<Object>) em.createNativeQuery(sql).getResultList());
        List<Object[]> out = new ArrayList<>();
        for (Object o : raw) {
            out.add(o instanceof Object[] a ? a : new Object[] {o});
        }
        return out;
    }

    /** seat.events written to the outbox for a section (outbox id order = emission order). */
    public List<SpecificRecord> events(String sectionId) {
        List<SpecificRecord> out = new ArrayList<>();
        for (Object[] r : rows("select event_type, payload_json from outbox where topic = 'seat.events' "
                + "and msg_key = '" + sectionId + "' order by id")) {
            out.add(registry.fromJson((String) r[0], (String) r[1]));
        }
        return out;
    }

    public <T extends SpecificRecord> List<T> events(String sectionId, Class<T> type) {
        return events(sectionId).stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** Back to pristine for one section (seats, its events, nothing else). */
    public void fresh(String sectionId) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("update seat set state='AVAILABLE', order_id=null, held_until=null, buyer_id=null, "
                    + "version=0 where section_id = '" + sectionId + "'").executeUpdate();
            em.createNativeQuery("delete from outbox where msg_key = '" + sectionId + "'").executeUpdate();
        });
    }

    public Object[] seat(String seatId) {
        return rows("select state, order_id::text, version, buyer_id from seat where seat_id = '" + seatId + "'").get(0);
    }

    public void expireNow(String orderId) {
        exec("update seat set held_until = now() - interval '1 minute' where order_id = CAST('" + orderId
                + "' AS uuid) and state = 'HELD'");
    }

    public static String order() {
        return UUID.randomUUID().toString();
    }

    public static String seatId(String section, String row, int n) {
        return VenueLayout.seatId(section, row, n);
    }

    public static List<String> seats(String section, int... numbers) {
        List<String> l = new ArrayList<>();
        for (int n : numbers) {
            l.add(seatId(section, "A", n));
        }
        return l;
    }

    /** i = 0..29 -> rows A,B,C x 10. */
    public static String seatByIndex(String section, int i) {
        return seatId(section, String.valueOf("ABC".charAt(i / 10)), i % 10 + 1);
    }

    public static HoldSeats hold(String order, String section, List<String> seats, int secs) {
        return HoldSeats.newBuilder().setMeta(EventMetas.of("order-service", order)).setOrderId(order)
                .setBuyerId("buyer-" + order.substring(0, 4)).setConcertId(CONCERT).setSectionId(section)
                .setSeatIds(seats).setHoldSeconds(secs).build();
    }

    public static ConfirmSeats confirm(String order, String section) {
        return ConfirmSeats.newBuilder().setMeta(EventMetas.of("order-service", order)).setOrderId(order)
                .setConcertId(CONCERT).setSectionId(section).build();
    }

    public static ReleaseSeats release(String order, String section, String reason) {
        return ReleaseSeats.newBuilder().setMeta(EventMetas.of("order-service", order)).setOrderId(order)
                .setConcertId(CONCERT).setSectionId(section).setReason(reason).build();
    }

    public static ExtendHold extend(String order, String section, int secs) {
        return ExtendHold.newBuilder().setMeta(EventMetas.of("order-service", order)).setOrderId(order)
                .setConcertId(CONCERT).setSectionId(section).setExtraSeconds(secs).build();
    }
}
