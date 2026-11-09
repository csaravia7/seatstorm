package io.seatstorm.platform.outbox;

import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.event.Observes;
import java.util.concurrent.atomic.AtomicBoolean;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import io.smallrye.reactive.messaging.MutinyEmitter;
import io.seatstorm.contracts.EventRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.avro.specific.SpecificRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Message;

/**
 * Publishes pending outbox rows in id order. A Postgres transaction-level advisory lock guarantees
 * a single active relay across replicas (keeps per-key order). Delivery is at-least-once.
 */
@ApplicationScoped
public class OutboxRelay {

    /** Advisory lock id shared by all relays of one database. */
    public static final long LOCK_ID = 727001L;

    @Inject
    EntityManager em;

    @Inject
    EventRegistry registry;

    @Inject
    @Channel("outbox-out")
    MutinyEmitter<SpecificRecord> emitter;

    @ConfigProperty(name = "seatstorm.outbox.relay.batch-size", defaultValue = "200")
    int batchSize;

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "seatstorm.outbox.relay.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "seatstorm.outbox.relay.interval", defaultValue = "200ms")
    Duration interval;

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile long timerId = -1;

    /**
     * Timer based (Quarkus @Scheduled has a 1 s granularity, too coarse for the demo).
     * Set seatstorm.outbox.relay.enabled=false to disable (tests drive {@link #relayOnce()} by hand).
     */
    void onStart(@Observes StartupEvent ev) {
        if (!enabled) {
            return;
        }
        timerId = vertx.setPeriodic(interval.toMillis(), id -> {
            if (running.compareAndSet(false, true)) { // SKIP concurrent executions
                vertx.<Void>executeBlocking(() -> {
                    relayOnce();
                    return null;
                }, true).onComplete(r -> {
                    running.set(false);
                    if (r.failed()) {
                        Log.warn("Outbox relay tick failed", r.cause());
                    }
                });
            }
        });
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
        }
    }

    /** Relays one batch. Returns the number of messages published (0 when another replica holds the lock). */
    @Transactional
    public int relayOnce() {
        Boolean locked = (Boolean) em.createNativeQuery("select pg_try_advisory_xact_lock(" + LOCK_ID + ")")
                .getSingleResult();
        if (!Boolean.TRUE.equals(locked)) {
            return 0; // another replica is relaying
        }
        List<OutboxEntry> batch = OutboxEntry.<OutboxEntry>find("sentAt is null order by id")
                .page(0, batchSize).list();
        int sent = 0;
        for (OutboxEntry e : batch) {
            try {
                SpecificRecord record = registry.fromJson(e.eventType, e.payloadJson);
                var md = OutgoingKafkaRecordMetadata.<String>builder().withTopic(e.topic).withKey(e.msgKey);
                if (e.partitionHint != null) {
                    md.withPartition(e.partitionHint);
                }
                emitter.sendMessage(Message.of(record).addMetadata(md.build()))
                        .await().atMost(Duration.ofSeconds(5));
                e.sentAt = Instant.now();
                sent++;
            } catch (RuntimeException ex) {
                // Stop here to keep order; rows already acked stay marked and the rest retries next tick.
                Log.warnf(ex, "Outbox relay stopped at id=%d (%s): %s", e.id, e.eventType, ex.toString());
                break;
            }
        }
        return sent;
    }
}
