package io.seatstorm.platform.outbox;

import io.seatstorm.contracts.Sections;
import org.apache.avro.specific.SpecificRecord;

/** Transactional outbox. Rows are published to Kafka by {@link OutboxRelay}. */
public interface Outbox {
    /**
     * Must be called inside the same transaction that changes domain state.
     * Throws {@link IllegalStateException} when there is no active transaction.
     *
     * @param topic     target topic
     * @param key       Kafka message key
     * @param partition explicit partition, or null to let Kafka choose from the key
     * @param event     any top-level Avro event (must carry {@code meta})
     */
    void add(String topic, String key, Integer partition, SpecificRecord event);

    /** Key-only variant (partition chosen by Kafka from the key). */
    default void add(String topic, String key, SpecificRecord event) {
        add(topic, key, null, event);
    }

    /** seat.commands / seat.events convention: key = sectionId, partition = section index. */
    default void addBySection(String topic, String sectionId, SpecificRecord event) {
        add(topic, sectionId, Sections.indexOf(sectionId), event);
    }
}
