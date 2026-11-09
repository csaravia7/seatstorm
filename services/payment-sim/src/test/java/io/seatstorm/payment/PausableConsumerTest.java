package io.seatstorm.payment;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.seatstorm.contracts.*;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/** ADR-17: mode=PAUSED pauses the payment-commands channel; commands pile up (lag grows); OK drains them. */
@QuarkusTest
class PausableConsumerTest {
    static final String GROUP = "payment-sim-payment-commands";

    @Inject Fx fx;
    @Inject PaymentPauser pauser;

    private static void mode(String m) {
        given().header("X-Admin-Token", "change-me-admin").contentType("application/json")
                .body(Map.of("mode", m)).put("/admin/mode").then().statusCode(200);
    }

    /** Messages published to payment.commands minus offsets committed by the group = group lag. */
    private long lag(KafkaCompanion kc) {
        long published = fx.count("select count(*) from outbox where topic = 'payment.commands' and sent_at is not null");
        long committed = 0;
        for (OffsetAndMetadata o : kc.consumerGroups().offsets(GROUP).values()) {
            committed += o.offset();
        }
        return published - committed;
    }

    @Test
    void pausedChannelAccumulatesLagAndResumesDraining() {
        String bootstrap = ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String.class);
        try (KafkaCompanion kc = new KafkaCompanion(bootstrap)) {
            // warm-up: the consumer is alive and the group has committed offsets
            String warm = Fx.oid();
            fx.send(Fx.request(warm, 1), warm);
            Awaitility.await().atMost(Duration.ofSeconds(60)).until(() -> "AUTHORIZED".equals(fx.paymentStatus(warm)));
            assertFalse(pauser.isPaused());

            long t0 = System.nanoTime();
            mode("PAUSED");
            Awaitility.await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(20)).until(pauser::isPaused);
            long pauseMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(pauseMs < 1500, "pause took " + pauseMs + " ms");

            List<String> ids = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String id = Fx.oid();
                ids.add(id);
                fx.send(Fx.request(id, 100 + i), id);
            }
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> fx.count(
                    "select count(*) from outbox where topic = 'payment.commands' and sent_at is null") == 0);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (String id : ids) {
                assertNull(fx.paymentStatus(id), "processed while paused");
            }
            assertEquals(6, lag(kc), "lag grows while paused");

            mode("OK");
            Awaitility.await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(20)).until(() -> !pauser.isPaused());
            Awaitility.await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                for (String id : ids) {
                    assertEquals("AUTHORIZED", fx.paymentStatus(id));
                    assertEquals(1, fx.emitted(id).size());
                }
            });
            Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> lag(kc) == 0);
        }
    }

    @Test
    void resetWhilePausedResumesTheChannel() {
        mode("PAUSED");
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(pauser::isPaused);
        fx.reset();
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> !pauser.isPaused());
    }
}
