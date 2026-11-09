package io.seatstorm.platform;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.seatstorm.contracts.Topics;
import io.seatstorm.platform.inbox.Inbox;
import io.seatstorm.platform.outbox.Outbox;
import io.seatstorm.platform.outbox.OutboxCleanup;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class InboxCleanupTest {
    @Inject Inbox inbox;
    @Inject Outbox outbox;
    @Inject OutboxCleanup cleanup;
    @Inject TestSupport support;

    @BeforeEach
    void clean() {
        support.clean();
    }

    @Test
    void firstTimeIsTrueOnlyOnce() {
        String id = UUID.randomUUID().toString();
        assertTrue(QuarkusTransaction.requiringNew().call(() -> inbox.firstTime("svc.ch", id)));
        assertFalse(QuarkusTransaction.requiringNew().call(() -> inbox.firstTime("svc.ch", id)));
        // another consumer has its own namespace
        assertTrue(QuarkusTransaction.requiringNew().call(() -> inbox.firstTime("other.ch", id)));
        assertEquals(2, support.count("select count(*) from inbox"));
    }

    @Test
    void outboxAddWithoutTransactionFails() {
        assertThrows(IllegalStateException.class,
                () -> outbox.add(Topics.DEMO_CONTROL, "r1", null, TestSupport.event("r1", "x")));
        assertEquals(0, support.count("select count(*) from outbox"));
    }

    @Test
    void cleanupDeletesOldSentOutboxAndOldInbox() {
        for (int i = 0; i < 3; i++) {
            String r = "c" + i;
            QuarkusTransaction.requiringNew().run(
                    () -> outbox.add(Topics.DEMO_CONTROL, r, null, TestSupport.event(r, "x")));
        }
        // 0: sent 2h ago (deleted), 1: sent just now (kept), 2: pending and old (kept)
        support.exec("update outbox set sent_at = now() - interval '2 hours' where msg_key = 'c0'");
        support.exec("update outbox set sent_at = now() where msg_key = 'c1'");
        support.exec("update outbox set created_at = now() - interval '3 days' where msg_key = 'c2'");
        QuarkusTransaction.requiringNew().run(() -> {
            inbox.firstTime("a", UUID.randomUUID().toString());
            inbox.firstTime("a", UUID.randomUUID().toString());
        });
        support.exec("update inbox set processed_at = now() - interval '25 hours' where consumer = 'a' "
                + "and event_id = (select event_id from inbox where consumer = 'a' limit 1)");

        int[] deleted = cleanup.cleanup(Duration.ofHours(1), Duration.ofHours(24));
        assertArrayEquals(new int[] {1, 1}, deleted);
        assertEquals(2, support.count("select count(*) from outbox"));
        assertEquals(1, support.count("select count(*) from inbox"));
    }
}
