package io.seatstorm.catalog;

import io.apicurio.registry.serde.avro.AvroKafkaDeserializer;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.microprofile.config.ConfigProvider;

@ApplicationScoped
public class TestSupport {
    @Inject
    EntityManager em;

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
        task.awaitCompletion(Duration.ofSeconds(60));
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

    public long count(String sql) {
        return QuarkusTransaction.requiringNew().call(
                () -> ((Number) em.createNativeQuery(sql).getSingleResult()).longValue());
    }

    public void exec(String sql) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(sql).executeUpdate());
    }

    /** Waits until the relay has published everything in the outbox. */
    public void awaitRelayed() {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30))
                .until(() -> count("select count(*) from outbox where sent_at is null") == 0);
    }
}
