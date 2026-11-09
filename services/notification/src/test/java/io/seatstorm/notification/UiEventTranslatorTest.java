package io.seatstorm.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.OrderStatusChanged;
import io.seatstorm.contracts.ResetRequested;
import io.seatstorm.contracts.SaleClosed;
import io.seatstorm.contracts.SaleOpened;
import io.seatstorm.contracts.SeatChange;
import io.seatstorm.contracts.SeatsHeld;
import io.seatstorm.contracts.SeatsRejected;
import io.seatstorm.contracts.SeatsReleased;
import io.seatstorm.contracts.SeatsSold;
import io.seatstorm.contracts.TicketIssued;
import java.util.List;
import java.util.Set;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.Test;

class UiEventTranslatorTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String C = "neon-tide-2026";
    static final String ORDER = "11111111-1111-1111-1111-111111111111";
    final UiEventTranslator t = new UiEventTranslator(60);

    static io.seatstorm.contracts.EventMeta meta(long at) {
        var m = EventMetas.of("test", ORDER);
        m.setOccurredAtMs(at);
        return m;
    }

    JsonNode json(UiMessage m) {
        return JSON.valueToTree(m.body());
    }

    /** Minimal structural schema check of spec 6.5: required fields and JSON types per message type. */
    static void assertSchema(JsonNode n) {
        switch (n.get("type").asText()) {
            case "seat" -> {
                assertFields(n, Set.of("type", "concertId", "sectionId", "seatId", "state", "version", "at"));
                assertTrue(n.get("version").isIntegralNumber() && n.get("at").isIntegralNumber());
                assertTrue(Set.of("AVAILABLE", "HELD", "SOLD").contains(n.get("state").asText()));
            }
            case "sale" -> {
                assertFields(n, Set.of("type", "concertId", "status"));
                assertTrue(Set.of("OPEN", "CLOSED").contains(n.get("status").asText()));
            }
            case "order" -> {
                assertFields(n, Set.of("type", "buyerId", "orderId", "status", "reason", "heldUntilMs", "at"));
                assertTrue(n.get("reason").isNull() || n.get("reason").isTextual());
                assertTrue(n.get("heldUntilMs").isNull() || n.get("heldUntilMs").isIntegralNumber());
            }
            case "notify" -> {
                assertFields(n, Set.of("type", "buyerId", "level", "text"));
                assertTrue(Set.of("info", "success", "warning", "error").contains(n.get("level").asText()));
            }
            case "feed" -> assertFields(n, Set.of("type", "name", "detail", "orderId", "at"));
            case "reset" -> assertFields(n, Set.of("type"));
            default -> throw new AssertionError("unknown type " + n);
        }
    }

    static void assertFields(JsonNode n, Set<String> fields) {
        Set<String> actual = new java.util.HashSet<>();
        n.fieldNames().forEachRemaining(actual::add);
        assertEquals(fields, actual);
    }

    @Test
    void seatChangesProduceSeatMessagesKeyedBySection() {
        var ev = SeatsHeld.newBuilder().setMeta(meta(1000)).setOrderId(ORDER).setBuyerId("b1").setConcertId(C)
                .setSectionId("PLATEA-A").setHeldUntilMs(61000)
                .setChanges(List.of(new SeatChange("PLATEA-A:B07", "HELD", 12L), new SeatChange("PLATEA-A:B08", "HELD", 3L)))
                .build();
        var out = t.translate(ev);
        var seats = out.stream().filter(m -> "seat".equals(m.body().get("type"))).toList();
        assertEquals(2, seats.size());
        assertEquals("PLATEA-A", seats.get(0).key());
        JsonNode n = json(seats.get(0));
        assertEquals("PLATEA-A:B07", n.get("seatId").asText());
        assertEquals(12, n.get("version").asLong());
        assertEquals(1000, n.get("at").asLong());
        out.forEach(m -> assertSchema(json(m)));
        assertEquals(1, out.stream().filter(UiMessage::isFeed).count());
    }

    @Test
    void soldAndReleasedProduceSeatMessages() {
        var sold = SeatsSold.newBuilder().setMeta(meta(1)).setOrderId(ORDER).setBuyerId("b1").setConcertId(C)
                .setSectionId("GRADA-N").setChanges(List.of(new SeatChange("GRADA-N:A01", "SOLD", 2L))).build();
        assertEquals("SOLD", json(t.translate(sold).get(0)).get("state").asText());
        var rel = SeatsReleased.newBuilder().setMeta(meta(1)).setOrderId(ORDER).setConcertId(C).setSectionId("GRADA-N")
                .setReason("RESET").setChanges(List.of(new SeatChange("GRADA-N:A01", "AVAILABLE", 3L))).build();
        var out = t.translate(rel);
        assertEquals(1, out.size()); // RESET release has no feed line
        var exp = SeatsReleased.newBuilder(rel).setReason("HOLD_EXPIRED").build();
        assertEquals(2, t.translate(exp).size());
    }

    private OrderStatusChanged status(String st, String reason, Long until) {
        return OrderStatusChanged.newBuilder().setMeta(meta(10_000)).setOrderId(ORDER).setBuyerId("b_91f2")
                .setStatus(st).setReason(reason).setHeldUntilMs(until).build();
    }

    @Test
    void orderStatusProducesOrderAndNotify() {
        var out = t.translate(status("HELD", null, 70_000L));
        assertEquals(2, out.size());
        JsonNode order = json(out.get(0));
        assertEquals("b_91f2", out.get(0).key());
        assertEquals("HELD", order.get("status").asText());
        assertTrue(order.get("reason").isNull());
        assertEquals(70_000, order.get("heldUntilMs").asLong());
        assertEquals("Retuvimos tus asientos por 60 s", json(out.get(1)).get("text").asText());
        out.forEach(m -> assertSchema(json(m)));

        assertEquals("Alguien llegó antes", json(t.translate(status("REJECTED", "SEAT_TAKEN", null)).get(1)).get("text").asText());
        assertEquals("Se acabó el tiempo", json(t.translate(status("EXPIRED", "HOLD_EXPIRED", null)).get(1)).get("text").asText());
        var failed = t.translate(status("CANCELLED", "PAYMENT_FAILED", null));
        assertEquals("No pudimos procesar el pago", json(failed.stream().filter(m -> "notify".equals(m.body().get("type")))
                .findFirst().orElseThrow()).get("text").asText());
        assertEquals("PaymentFailed", failed.stream().filter(UiMessage::isFeed).findFirst().orElseThrow().body().get("name"));
        failed.forEach(m -> assertSchema(json(m)));
    }

    @Test
    void otherStatusesOnlyProduceOrderMessage() {
        for (String s : List.of("PLACED", "PAYING", "CONFIRMING", "CONFIRMED", "REFUNDING")) {
            assertEquals(1, t.translate(status(s, null, null)).size(), s);
        }
        var cancelled = t.translate(status("CANCELLED", "USER_CANCELLED", null));
        assertEquals(1, cancelled.size());
        assertNull(cancelled.get(0).body().get("heldUntilMs"));
    }

    @Test
    void ticketIssuedProducesSuccessNotify() {
        var ev = TicketIssued.newBuilder().setMeta(meta(1)).setTicketId("t1").setOrderId(ORDER).setBuyerId("b1")
                .setConcertId(C).setSeatIds(List.of("PLATEA-A:B07", "PLATEA-A:B08")).setQrPayload("qr").build();
        var out = t.translate(ev);
        assertEquals(1, out.size());
        JsonNode n = json(out.get(0));
        assertEquals("success", n.get("level").asText());
        assertEquals("¡Listo! Tus asientos en Platea A: B07, B08", n.get("text").asText());
        assertSchema(n);
    }

    @Test
    void saleAndResetAndRejectedFeed() {
        var open = t.translate(SaleOpened.newBuilder().setMeta(meta(1)).setConcertId(C).build());
        assertEquals("OPEN", json(open.get(0)).get("status").asText());
        assertSchema(json(open.get(0)));
        var closed = t.translate(SaleClosed.newBuilder().setMeta(meta(1)).setConcertId(C).build());
        assertEquals("CLOSED", json(closed.get(0)).get("status").asText());
        var reset = t.translate(ResetRequested.newBuilder().setMeta(meta(1)).setResetId("r1").build());
        assertEquals("{\"type\":\"reset\"}", json(reset.get(0)).toString());
        var rej = t.translate(SeatsRejected.newBuilder().setMeta(meta(5)).setOrderId(ORDER).setBuyerId("b1").setConcertId(C)
                .setSectionId("PLATEA-A").setReason("SEAT_TAKEN").setTakenSeatIds(List.of("PLATEA-A:B07")).build());
        assertEquals(1, rej.size());
        assertEquals("SeatsRejected", rej.get(0).body().get("name"));
        assertEquals("PLATEA-A:B07 ocupado", rej.get(0).body().get("detail"));
        assertSchema(json(rej.get(0)));
    }

    @Test
    void unrelatedRecordsAreIgnored() {
        SpecificRecord other = io.seatstorm.contracts.ResetCompleted.newBuilder().setMeta(meta(1)).setResetId("r")
                .setService("x").build();
        assertTrue(t.translate(other).isEmpty());
    }

    @Test
    void feedLimiterCapsAtThirtyPerSecondButNotSeats() {
        long[] now = {5_000};
        var limiter = new FeedLimiter(30, () -> now[0]);
        int feeds = 0;
        int seats = 0;
        for (int i = 0; i < 100; i++) {
            var ev = SeatsHeld.newBuilder().setMeta(meta(1)).setOrderId(ORDER).setBuyerId("b").setConcertId(C)
                    .setSectionId("GRADA-S").setHeldUntilMs(1).setChanges(List.of(new SeatChange("GRADA-S:A01", "HELD", (long) i)))
                    .build();
            for (UiMessage m : t.translate(ev)) {
                if (m.isFeed()) {
                    if (limiter.tryAcquire()) {
                        feeds++;
                    }
                } else {
                    seats++;
                }
            }
            now[0] += 5; // 100 events in 500 ms
        }
        assertEquals(30, feeds);
        assertEquals(100, seats);
        now[0] += 1000;
        assertTrue(limiter.tryAcquire());
    }
}
