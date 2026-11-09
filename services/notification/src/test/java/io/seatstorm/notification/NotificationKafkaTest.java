package io.seatstorm.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.OrderStatusChanged;
import io.seatstorm.contracts.ResetRequested;
import io.seatstorm.contracts.SaleOpened;
import io.seatstorm.contracts.SeatChange;
import io.seatstorm.contracts.SeatsHeld;
import io.seatstorm.contracts.Topics;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

@QuarkusTest
class NotificationKafkaTest {

    @Inject TestProducer producer;

    @Test
    void translatesAvroEventsToJsonOnUiEvents() throws Exception {
        String c = "neon-tide-2026";
        String order = "22222222-2222-2222-2222-222222222222";
        long now = System.currentTimeMillis();
        var held = SeatsHeld.newBuilder().setMeta(EventMetas.of("test", order)).setOrderId(order).setBuyerId("b_1")
                .setConcertId(c).setSectionId("PLATEA-A").setHeldUntilMs(now + 60000)
                .setChanges(List.of(new SeatChange("PLATEA-A:B07", "HELD", 12L))).build();
        var status = OrderStatusChanged.newBuilder().setMeta(EventMetas.of("test", order)).setOrderId(order)
                .setBuyerId("b_1").setStatus("REJECTED").setReason("SEAT_TAKEN").build();
        producer.send(Topics.SEAT_EVENTS, "PLATEA-A", held);
        producer.send(Topics.ORDER_EVENTS, order, status);
        producer.send(Topics.SALE_EVENTS, c, SaleOpened.newBuilder().setMeta(EventMetas.of("test", c)).setConcertId(c).build());
        producer.send(Topics.DEMO_CONTROL, "r1", ResetRequested.newBuilder().setMeta(EventMetas.of("test", "r1")).setResetId("r1").build());

        // seat + feed, order + notify, sale, reset = 6
        List<ConsumerRecord<String, String>> recs = new ArrayList<>();
        try (KafkaCompanion kc = new KafkaCompanion(ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String.class))) {
            var task = kc.consumeStrings().withProp("auto.offset.reset", "earliest")
                    .withGroupId("test-ui-" + System.nanoTime()).fromTopics(Topics.UI_EVENTS, 6);
            task.awaitCompletion(Duration.ofSeconds(60));
            task.getRecords().forEach(recs::add);
        }
        assertEquals(6, recs.size());
        ObjectMapper om = new ObjectMapper();
        var byType = new java.util.HashMap<String, List<ConsumerRecord<String, String>>>();
        for (var r : recs) {
            byType.computeIfAbsent(om.readTree(r.value()).get("type").asText(), k -> new ArrayList<>()).add(r);
        }
        var seat = byType.get("seat").get(0);
        assertEquals("PLATEA-A", seat.key());
        JsonNode s = om.readTree(seat.value());
        assertEquals("PLATEA-A:B07", s.get("seatId").asText());
        assertEquals(12, s.get("version").asLong());
        assertEquals("HELD", s.get("state").asText());
        var ord = byType.get("order").get(0);
        assertEquals("b_1", ord.key());
        JsonNode o = om.readTree(ord.value());
        assertEquals("REJECTED", o.get("status").asText());
        assertTrue(o.get("heldUntilMs").isNull());
        assertEquals("Alguien llegó antes", om.readTree(byType.get("notify").get(0).value()).get("text").asText());
        assertEquals("OPEN", om.readTree(byType.get("sale").get(0).value()).get("status").asText());
        assertEquals("{\"type\":\"reset\"}", byType.get("reset").get(0).value());
        assertEquals(1, byType.get("feed").size());
    }
}
