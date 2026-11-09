package io.seatstorm.platform.outbox;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Deletes sent outbox rows (default > 1 h) and inbox rows (default > 24 h). */
@ApplicationScoped
public class OutboxCleanup {

    @Inject
    EntityManager em;

    @ConfigProperty(name = "seatstorm.outbox.cleanup.sent-retention", defaultValue = "1h")
    Duration sentRetention;

    @ConfigProperty(name = "seatstorm.inbox.cleanup.retention", defaultValue = "24h")
    Duration inboxRetention;

    @Scheduled(every = "{seatstorm.outbox.cleanup.every}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void tick() {
        cleanup();
    }

    /** Returns {deletedOutbox, deletedInbox}. */
    public int[] cleanup() {
        return cleanup(sentRetention, inboxRetention);
    }

    @Transactional
    public int[] cleanup(Duration outboxSentOlderThan, Duration inboxOlderThan) {
        Instant now = Instant.now();
        int o = em.createNativeQuery("delete from outbox where sent_at is not null and sent_at < ?1")
                .setParameter(1, now.minus(outboxSentOlderThan)).executeUpdate();
        int i = em.createNativeQuery("delete from inbox where processed_at < ?1")
                .setParameter(1, now.minus(inboxOlderThan)).executeUpdate();
        return new int[] {o, i};
    }
}
