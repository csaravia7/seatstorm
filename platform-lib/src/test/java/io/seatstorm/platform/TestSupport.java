package io.seatstorm.platform;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.ResetCompleted;
import io.seatstorm.platform.outbox.Outbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.config.ConfigProvider;

@ApplicationScoped
public class TestSupport {
    @Inject
    EntityManager em;

    @Inject
    Outbox outbox;

    public static String bootstrap() {
        return ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String.class);
    }

    public static String registryUrl() {
        return ConfigProvider.getConfig()
                .getValue("mp.messaging.connector.smallrye-kafka.apicurio.registry.url", String.class);
    }

    /** Consumes n records from a topic and decodes values with the Apicurio Avro deserializer. */
    public static java.util.List<org.apache.kafka.clients.consumer.ConsumerRecord<String, org.apache.avro.specific.SpecificRecord>> consume(
            io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion kc, String topic, int n) {
        var task = kc.consumeWithDeserializers(
                        "org.apache.kafka.common.serialization.StringDeserializer",
                        "org.apache.kafka.common.serialization.ByteArrayDeserializer")
                .withProp("auto.offset.reset", "earliest")
                .withGroupId("test-" + topic + "-" + System.nanoTime())
                .fromTopics(topic, n);
        task.awaitCompletion(java.time.Duration.ofSeconds(60));
        var de = new io.apicurio.registry.serde.avro.AvroKafkaDeserializer<org.apache.avro.specific.SpecificRecord>();
        de.configure(java.util.Map.of("apicurio.registry.url", registryUrl(),
                "apicurio.registry.use-specific-avro-reader", "true"), false);
        var out = new java.util.ArrayList<org.apache.kafka.clients.consumer.ConsumerRecord<String, org.apache.avro.specific.SpecificRecord>>();
        for (Object o : task.getRecords()) {
            @SuppressWarnings("unchecked")
            var r = (org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]>) o;
            out.add(new org.apache.kafka.clients.consumer.ConsumerRecord<>(r.topic(), r.partition(), r.offset(),
                    r.key(), de.deserialize(r.topic(), r.value())));
        }
        return out;
    }

    public void clean() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("delete from outbox").executeUpdate();
            em.createNativeQuery("delete from inbox").executeUpdate();
        });
    }

    public long count(String sql) {
        return QuarkusTransaction.requiringNew().call(
                () -> ((Number) em.createNativeQuery(sql).getSingleResult()).longValue());
    }

    public void exec(String sql) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(sql).executeUpdate());
    }

    public static ResetCompleted event(String resetId, String service) {
        return ResetCompleted.newBuilder().setMeta(EventMetas.of("test", resetId))
                .setResetId(resetId).setService(service).build();
    }
}
