package io.seatstorm.platform.outbox;

import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.EventRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;

@ApplicationScoped
public class OutboxService implements Outbox {

    @Inject
    TransactionSynchronizationRegistry tx;

    @Inject
    EventRegistry registry;

    @Override
    public void add(String topic, String key, Integer partition, SpecificRecord event) {
        if (tx.getTransactionStatus() != Status.STATUS_ACTIVE) {
            throw new IllegalStateException("Outbox.add requires an active transaction");
        }
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(event, "event");
        OutboxEntry e = new OutboxEntry();
        e.eventId = UUID.fromString(EventMetas.idOf(event));
        e.topic = topic;
        e.msgKey = key;
        e.partitionHint = partition;
        e.eventType = event.getClass().getSimpleName();
        e.payloadJson = registry.toJson(event);
        e.createdAt = Instant.now();
        e.persist();
    }
}
