package io.seatstorm.inventory;

import static io.seatstorm.inventory.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.seatstorm.contracts.SeatsConfirmFailed;
import io.seatstorm.contracts.SeatsHeld;
import io.seatstorm.contracts.SeatsReleased;
import io.seatstorm.contracts.Sections;
import io.seatstorm.contracts.Topics;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** Real @Scheduled job (every 1 s) and the full Kafka path: hold(1 s) -> expiry -> confirm fails. */
@QuarkusTest
@TestProfile(ExpirySchedulerTest.EveryOneSecond.class)
class ExpirySchedulerTest {

    public static class EveryOneSecond implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("seatstorm.expiry.every", "1s");
        }
    }

    @Inject TestSupport t;

    @Test
    void scheduledJobReleasesExpiredHoldAndLateConfirmFails() {
        String sec = "PALCO-1"; // index 4
        t.fresh(sec);
        String o = order();
        try (KafkaCompanion kc = new KafkaCompanion(bootstrap())) {
            send(kc, Topics.SEAT_COMMANDS, sec, Sections.indexOf(sec), List.of(hold(o, sec, seats(sec, 1, 2), 1)));
            Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> t.events(sec, SeatsHeld.class).size() == 1);
            Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> t.events(sec, SeatsReleased.class).size() == 1);
            SeatsReleased r = t.events(sec, SeatsReleased.class).get(0);
            assertEquals("HOLD_EXPIRED", r.getReason().toString());
            assertEquals(o, r.getOrderId().toString());
            assertEquals(2, r.getChanges().size());
            assertEquals("AVAILABLE", t.seat(seatId(sec, "A", 1))[0]);

            send(kc, Topics.SEAT_COMMANDS, sec, Sections.indexOf(sec), List.of(confirm(o, sec)));
            Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> t.events(sec, SeatsConfirmFailed.class).size() == 1);
            assertEquals("HOLD_EXPIRED", t.events(sec, SeatsConfirmFailed.class).get(0).getReason().toString());
            assertEquals("AVAILABLE", t.seat(seatId(sec, "A", 2))[0]);
        }
    }
}
