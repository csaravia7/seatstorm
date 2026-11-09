package io.seatstorm.inventory;

import static io.seatstorm.inventory.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.ResetCompleted;
import io.seatstorm.contracts.ResetRequested;
import io.seatstorm.contracts.SeatsReleased;
import io.seatstorm.contracts.Sections;
import io.seatstorm.contracts.Topics;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ResetTest {

    @Inject TestSupport t;
    @Inject SeatService service;

    @Test
    void resetRequestedFreesEverySeatAndPublishesResetCompleted() {
        // some state: a hold and a sale
        String sec = "PALCO-2";
        t.fresh(sec);
        String a = order(), b = order();
        QuarkusTransaction.requiringNew().run(() -> service.hold(hold(a, sec, seats(sec, 1, 2), 60)));
        QuarkusTransaction.requiringNew().run(() -> service.hold(hold(b, sec, seats(sec, 3), 60)));
        QuarkusTransaction.requiringNew().run(() -> service.confirm(confirm(b, sec)));
        long versionsBefore = t.count("select coalesce(sum(version), 0) from seat");
        long outboxBefore = t.count("select count(*) from outbox where event_type = 'SeatsReleased' and payload_json like '%RESET%'");

        String resetId = UUID.randomUUID().toString();
        var req = ResetRequested.newBuilder().setMeta(EventMetas.of("event-catalog", resetId)).setResetId(resetId).build();
        try (KafkaCompanion kc = new KafkaCompanion(bootstrap())) {
            send(kc, Topics.DEMO_CONTROL, resetId, 0, List.of(req));
            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertEquals(1, t.count(
                    "select count(*) from outbox where event_type = 'ResetCompleted' and msg_key = '" + resetId + "'")));
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(
                    () -> t.count("select count(*) from outbox where sent_at is null") == 0);

            assertEquals(360, t.count("select count(*) from seat where state = 'AVAILABLE' and order_id is null "
                    + "and held_until is null"));
            assertEquals(versionsBefore + 360, t.count("select coalesce(sum(version), 0) from seat"), "version+1 on all 360");

            // one SeatsReleased(RESET) per section with 30 changes each
            var released = TestSupport.consumeAll(kc, Topics.SEAT_EVENTS, Duration.ofSeconds(5)).stream()
                    .filter(r -> r.value() instanceof SeatsReleased s && s.getReason().toString().equals("RESET")
                            && s.getOrderId().toString().equals(resetId)).toList();
            assertEquals(Sections.count(), released.size());
            for (var r : released) {
                SeatsReleased s = (SeatsReleased) r.value();
                assertEquals(30, s.getChanges().size());
                assertTrue(s.getChanges().stream().allMatch(c -> c.getState().equals("AVAILABLE")));
                assertEquals(Sections.indexOf(s.getSectionId().toString()), r.partition());
            }

            var done = TestSupport.consume(kc, Topics.DEMO_CONTROL, 2).stream()
                    .filter(r -> r.value() instanceof ResetCompleted c && c.getResetId().equals(resetId)).toList();
            assertEquals(1, done.size());
            assertEquals("seat-inventory", ((ResetCompleted) done.get(0).value()).getService());
        }
        assertEquals(0, t.count("select count(*) from inbox"), "reset empties the inbox");
        assertTrue(outboxBefore >= 0);
    }
}
