package io.seatstorm.platform;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.ResetCompleted;
import io.seatstorm.contracts.ResetRequested;
import io.seatstorm.contracts.Topics;
import io.seatstorm.platform.control.ResetHandler;
import io.seatstorm.platform.outbox.Outbox;
import io.seatstorm.platform.outbox.OutboxRelay;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

@QuarkusTest
class DemoControlTest {

    @jakarta.inject.Singleton
    public static class RecordingHandler implements ResetHandler {
        public final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public void reset(String resetId) {
            calls.add(resetId);
        }
    }

    @Inject Outbox outbox;
    @Inject OutboxRelay relay;
    @Inject TestSupport support;
    @Inject RecordingHandler handler;

    @Test
    void resetRequestedRunsHandlerClearsInboxAndPublishesResetCompleted() {
        support.clean();
        try (KafkaCompanion kc = new KafkaCompanion(TestSupport.bootstrap())) {
            // something in the inbox that the reset must wipe
            QuarkusTransaction.requiringNew().run(() -> support.exec(
                    "insert into inbox(consumer, event_id) values ('x', gen_random_uuid())"));

            var req = ResetRequested.newBuilder().setMeta(EventMetas.of("event-catalog", "reset-1"))
                    .setResetId("reset-1").build();
            QuarkusTransaction.requiringNew().run(() -> outbox.add(Topics.DEMO_CONTROL, "reset-1", null, req));
            assertEquals(1, relay.relayOnce()); // request goes out through the relay too

            // the consumer reacts: handler called and a ResetCompleted lands in the outbox
            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                assertTrue(handler.calls.contains("reset-1"));
                assertEquals(1, support.count(
                        "select count(*) from outbox where event_type = 'ResetCompleted' and sent_at is null"));
            });
            assertEquals(0, support.count("select count(*) from inbox"));
            assertEquals(1, relay.relayOnce());

            var records = TestSupport.consume(kc, Topics.DEMO_CONTROL, 2);
            var done = records.stream().map(r -> r.value())
                    .filter(v -> v instanceof ResetCompleted).map(v -> (ResetCompleted) v).findFirst().orElseThrow();
            assertEquals("reset-1", done.getResetId());
            assertEquals("platform-lib-test", done.getService());
        }
    }
}
