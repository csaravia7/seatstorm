package io.seatstorm.notification;

import io.smallrye.reactive.messaging.MutinyEmitter;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.avro.specific.SpecificRecord;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;

/** Test-only: publishes Avro domain events to any topic through the %test channel {@code test-out}. */
@ApplicationScoped
public class TestProducer {

    @Inject
    @Channel("test-out")
    MutinyEmitter<SpecificRecord> out;

    public void send(String topic, String key, SpecificRecord event) {
        out.sendMessageAndAwait(Message.of(event).addMetadata(
                OutgoingKafkaRecordMetadata.<String>builder().withTopic(topic).withKey(key).build()));
    }
}
