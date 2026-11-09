package io.seatstorm.platform.outbox;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox")
public class OutboxEntry extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    public UUID eventId;

    @Column(nullable = false)
    public String topic;

    @Column(name = "msg_key", nullable = false)
    public String msgKey;

    @Column(name = "partition_hint")
    public Integer partitionHint;

    @Column(name = "event_type", nullable = false)
    public String eventType;

    @Column(name = "payload_json", nullable = false)
    public String payloadJson;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "sent_at")
    public Instant sentAt;
}
