package io.seatstorm.query;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.seatstorm.contracts.EventMetas;
import io.seatstorm.contracts.OrderConfirmed;
import io.seatstorm.contracts.OrderPlaced;
import io.seatstorm.contracts.OrderStatusChanged;
import io.seatstorm.contracts.SaleOpened;
import io.seatstorm.contracts.SeatChange;
import io.seatstorm.contracts.SeatsHeld;
import io.seatstorm.contracts.SeatsRejected;
import io.seatstorm.contracts.SeatsReleased;
import io.seatstorm.contracts.SeatsSold;
import io.seatstorm.contracts.TicketIssued;
import io.seatstorm.contracts.Topics;
import io.seatstorm.contracts.VenueLayout;
import io.seatstorm.platform.control.ResetHandler;
import io.seatstorm.platform.outbox.Outbox;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SeatmapQueryTest {

    static final String C = VenueLayout.CONCERT_ID;

    @Inject EventConsumers consumers;
    @Inject AgroalDataSource ds;
    @Inject Outbox outbox;
    @Inject ResetHandler reset;

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("truncate order_view, order_timeline restart identity");
            s.execute("update seat_view set state='AVAILABLE', version=0");
            s.execute("delete from sale_view");
        }
    }

    // ---- helpers

    static SeatChange ch(String seat, String state, long v) {
        return new SeatChange(seat, state, v);
    }

    static <T extends SpecificRecord> Record<String, SpecificRecord> rec(T e) {
        return Record.of("k", e);
    }

    void held(String order, String section, long until, long ms, SeatChange... changes) {
        consumers.onSeat(rec(SeatsHeld.newBuilder().setMeta(meta(order, ms)).setOrderId(order).setBuyerId("b1")
                .setConcertId(C).setSectionId(section).setHeldUntilMs(until).setChanges(List.of(changes)).build()));
    }

    static io.seatstorm.contracts.EventMeta meta(String corr, long ms) {
        var m = EventMetas.of("test", corr);
        m.setOccurredAtMs(ms);
        return m;
    }

    void placed(String order, String buyer, String nick, String section, List<String> seats, long ms) {
        consumers.onOrder(rec(OrderPlaced.newBuilder().setMeta(meta(order, ms)).setOrderId(order).setBuyerId(buyer)
                .setNickname(nick).setConcertId(C).setSectionId(section).setSeatIds(seats).setTotalCents(9000 * seats.size())
                .setCurrency("USD").build()));
    }

    void status(String order, String buyer, String st, String reason, Long until, long ms) {
        consumers.onOrder(rec(OrderStatusChanged.newBuilder().setMeta(meta(order, ms)).setOrderId(order)
                .setBuyerId(buyer).setStatus(st).setReason(reason).setHeldUntilMs(until).build()));
    }

    void confirmed(String order, String buyer, String nick, String section, List<String> seats, long placedAt, long ms) {
        consumers.onOrder(rec(OrderConfirmed.newBuilder().setMeta(meta(order, ms)).setOrderId(order).setBuyerId(buyer)
                .setNickname(nick).setConcertId(C).setSectionId(section).setSeatIds(seats).setTotalCents(9000 * seats.size())
                .setPlacedAtMs(placedAt).build()));
    }

    // ---- US-E6-01

    @Test
    void seedsThreeHundredSixtySeats() {
        given().queryParam("concertId", C).get("/api/seatmap").then().statusCode(200)
                .body("sections", hasSize(12))
                .body("sections[0].sectionId", equalTo("PLATEA-A"))
                .body("sections[0].priceCents", equalTo(9000))
                .body("sections[4].priceCents", equalTo(15000))
                .body("sections[0].seats", hasSize(30))
                .body("sections[0].seats[0].seatId", equalTo("PLATEA-A:A01"))
                .body("sections[0].seats[0].row", equalTo("A"))
                .body("sections[0].seats[0].number", equalTo(1))
                .body("sections[0].seats[29].seatId", equalTo("PLATEA-A:C10"))
                .body("sections[11].seats", hasSize(30))
                .body("asOfMs", notNullValue());
        given().get("/api/stats").then().statusCode(200)
                .body("available", equalTo(360)).body("held", equalTo(0)).body("sold", equalTo(0))
                .body("saleStatus", equalTo("CLOSED"));
    }

    @Test
    void outOfOrderVersionsAreIgnored() {
        String o = UUID.randomUUID().toString();
        String seat = "PLATEA-A:B07";
        // version 3 (SOLD) arrives first, then stale version 2 (HELD) and a duplicate of 3
        consumers.onSeat(rec(SeatsSold.newBuilder().setMeta(meta(o, 3)).setOrderId(o).setBuyerId("b1").setConcertId(C)
                .setSectionId("PLATEA-A").setChanges(List.of(ch(seat, "SOLD", 3))).build()));
        held(o, "PLATEA-A", 99, 2, ch(seat, "HELD", 2));
        consumers.onSeat(rec(SeatsReleased.newBuilder().setMeta(meta(o, 4)).setOrderId(o).setConcertId(C)
                .setSectionId("PLATEA-A").setReason("RESET").setChanges(List.of(ch(seat, "AVAILABLE", 3))).build()));
        given().get("/api/stats").then().body("sold", equalTo(1)).body("held", equalTo(0)).body("available", equalTo(359));
        given().get("/api/seatmap").then().body("sections[0].seats.find { it.seatId == 'PLATEA-A:B07' }.state", equalTo("SOLD"))
                .body("sections[0].seats.find { it.seatId == 'PLATEA-A:B07' }.version", equalTo(3));
    }

    @Test
    void statsAfterSequence() {
        String o = UUID.randomUUID().toString();
        held(o, "PALCO-1", 100, 1, ch("PALCO-1:A01", "HELD", 1), ch("PALCO-1:A02", "HELD", 1), ch("PALCO-1:A03", "HELD", 1));
        consumers.onSeat(rec(SeatsSold.newBuilder().setMeta(meta(o, 2)).setOrderId(o).setBuyerId("b1").setConcertId(C)
                .setSectionId("PALCO-1").setChanges(List.of(ch("PALCO-1:A01", "SOLD", 2))).build()));
        consumers.onSeat(rec(SeatsReleased.newBuilder().setMeta(meta(o, 3)).setOrderId(o).setConcertId(C)
                .setSectionId("PALCO-1").setReason("HOLD_EXPIRED").setChanges(List.of(ch("PALCO-1:A03", "AVAILABLE", 2))).build()));
        given().queryParam("concertId", C).get("/api/stats").then()
                .body("available", equalTo(358)).body("held", equalTo(1)).body("sold", equalTo(1));
    }

    @Test
    void duplicateEventIsDeduplicatedByInbox() {
        String o = UUID.randomUUID().toString();
        var ev = SeatsRejected.newBuilder().setMeta(meta(o, 1)).setOrderId(o).setBuyerId("b1").setConcertId(C)
                .setSectionId("PLATEA-A").setReason("SEAT_TAKEN").setTakenSeatIds(List.of("PLATEA-A:A01")).build();
        consumers.onSeat(rec(ev));
        consumers.onSeat(rec(ev));
        given().get("/api/orders/" + o + "/timeline").then().body("$", hasSize(1));
    }

    @Test
    void unknownConcertIs404WithErrorBody() {
        given().queryParam("concertId", "nope").get("/api/seatmap").then().statusCode(404)
                .body("code", equalTo("NOT_FOUND")).body("message", notNullValue()).body("details", notNullValue());
    }

    // ---- US-E6-02

    @Test
    void orderLifecycleTimelineAndTicket() {
        String o = UUID.randomUUID().toString();
        List<String> seats = List.of("PLATEA-B:A01", "PLATEA-B:A02");
        long t = System.currentTimeMillis();
        placed(o, "b_me", "marco", "PLATEA-B", seats, t);
        held(o, "PLATEA-B", t + 60000, t + 10, ch(seats.get(0), "HELD", 1), ch(seats.get(1), "HELD", 1));
        status(o, "b_me", "HELD", null, t + 60000, t + 20);
        given().get("/api/buyers/b_me/orders").then().statusCode(200).body("[0].status", equalTo("HELD"))
                .body("[0].heldUntilMs", equalTo(t + 60000)).body("[0].ticketQr", nullValue());
        status(o, "b_me", "PAYING", null, t + 180000, t + 30);
        status(o, "b_me", "CONFIRMING", null, null, t + 40);
        confirmed(o, "b_me", "marco", "PLATEA-B", seats, t, t + 50);
        status(o, "b_me", "CONFIRMED", null, null, t + 50);
        consumers.onTicket(rec(TicketIssued.newBuilder().setMeta(meta(o, t + 60)).setTicketId(UUID.randomUUID().toString())
                .setOrderId(o).setBuyerId("b_me").setConcertId(C).setSeatIds(seats).setQrPayload("QR-PAYLOAD.SIG").build()));

        given().get("/api/buyers/b_me/orders?limit=5").then().statusCode(200).body("$", hasSize(1))
                .body("[0].orderId", equalTo(o)).body("[0].status", equalTo("CONFIRMED"))
                .body("[0].seatIds", contains("PLATEA-B:A01", "PLATEA-B:A02"))
                .body("[0].totalCents", equalTo(18000)).body("[0].sectionId", equalTo("PLATEA-B"))
                .body("[0].heldUntilMs", nullValue()).body("[0].ticketQr", equalTo("QR-PAYLOAD.SIG"));
        given().get("/api/orders/" + o + "/timeline").then().statusCode(200)
                .body("eventType", contains("OrderPlaced", "SeatsHeld", "OrderStatusChanged", "OrderStatusChanged",
                        "OrderStatusChanged", "OrderConfirmed", "OrderStatusChanged", "TicketIssued"))
                .body("[0].detail.seatIds", contains("PLATEA-B:A01", "PLATEA-B:A02"))
                .body("[2].detail.status", equalTo("HELD"));
    }

    @Test
    void ordersMostRecentFirstAndLimit() {
        long t = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            placed(UUID.randomUUID().toString(), "b_x", "x", "GRADA-N", List.of("GRADA-N:A0" + (i + 1)), t + i * 1000L);
        }
        given().get("/api/buyers/b_x/orders?limit=2").then().body("$", hasSize(2))
                .body("[0].seatIds[0]", equalTo("GRADA-N:A03")).body("[1].seatIds[0]", equalTo("GRADA-N:A02"));
        given().get("/api/buyers/nobody/orders").then().statusCode(200).body("$", empty());
    }

    @Test
    void staleStatusDoesNotRegress() {
        String o = UUID.randomUUID().toString();
        long t = System.currentTimeMillis();
        placed(o, "b_s", "s", "GRADA-N", List.of("GRADA-N:B01"), t);
        status(o, "b_s", "CONFIRMING", null, null, t + 50);
        status(o, "b_s", "PAYING", null, null, t + 40);
        given().get("/api/buyers/b_s/orders").then().body("[0].status", equalTo("CONFIRMING"));
    }

    @Test
    void ticketBeforeOrderCreatesStubThenOrderFillsIt() {
        String o = UUID.randomUUID().toString();
        long t = System.currentTimeMillis();
        consumers.onTicket(rec(TicketIssued.newBuilder().setMeta(meta(o, t + 5)).setTicketId(UUID.randomUUID().toString())
                .setOrderId(o).setBuyerId("b_t").setConcertId(C).setSeatIds(List.of("GRADA-S:A01")).setQrPayload("QR1").build()));
        placed(o, "b_t", "tina", "GRADA-S", List.of("GRADA-S:A01"), t);
        given().get("/api/buyers/b_t/orders").then().body("$", hasSize(1)).body("[0].ticketQr", equalTo("QR1"))
                .body("[0].totalCents", equalTo(9000));
    }

    @Test
    void timelineBadIdIs400() {
        given().get("/api/orders/not-a-uuid/timeline").then().statusCode(400).body("code", equalTo("VALIDATION_ERROR"));
    }

    // ---- US-E6-03

    @Test
    void searchAdjacentPairs() {
        long v = 1;
        // occupy PLATEA-A:A02 so row A of the section has 01 | 03..10
        held(UUID.randomUUID().toString(),"PLATEA-A", 1, 1, ch("PLATEA-A:A02", "HELD", v));
        given().queryParam("concertId", C).queryParam("sectionId", "PLATEA-A").queryParam("quantity", 2)
                .queryParam("adjacent", true).get("/api/seats/search").then().statusCode(200)
                .body("$", hasSize(5))
                .body("[0].seatIds", contains("PLATEA-A:A03", "PLATEA-A:A04"))
                .body("[0].sectionId", equalTo("PLATEA-A")).body("[0].priceCents", equalTo(9000));
    }

    @Test
    void searchNoResultsIsEmptyList() {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("update seat_view set state='SOLD', version=1 where section_id='GRADA-O'");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        given().queryParam("sectionId", "GRADA-O").queryParam("quantity", 2).queryParam("adjacent", true)
                .get("/api/seats/search").then().statusCode(200).body("$", empty());
        given().queryParam("quantity", 9).get("/api/seats/search").then().statusCode(400);
    }

    @Test
    void searchAcrossSectionsVaries() {
        given().queryParam("quantity", 3).get("/api/seats/search").then().body("$", hasSize(5))
                .body("[0].sectionId", equalTo("PLATEA-A")).body("[1].sectionId", equalTo("PLATEA-B"));
    }

    @Test
    void leaderboardOrdersByTicketsThenSpeed() {
        long t = System.currentTimeMillis();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String c = UUID.randomUUID().toString();
        confirmed(a, "b_a", "ana", "GRADA-N", List.of("GRADA-N:A01", "GRADA-N:A02"), t, t + 5000);
        confirmed(b, "b_b", "beto", "GRADA-N", List.of("GRADA-N:A03", "GRADA-N:A04"), t, t + 2000);
        confirmed(c, "b_c", "cris", "GRADA-N", List.of("GRADA-N:A05", "GRADA-N:A06", "GRADA-N:A07"), t, t + 9000);
        given().queryParam("concertId", C).queryParam("limit", 10).get("/api/leaderboard").then().statusCode(200)
                .body("nickname", contains("cris", "beto", "ana"))
                .body("tickets", contains(3, 2, 2)).body("fastestMs", contains(9000, 2000, 5000));
        given().queryParam("limit", 1).get("/api/leaderboard").then().body("$", hasSize(1));
    }

    // ---- reset, sale, kafka

    @Test
    void resetClearsOrdersOnly() {
        String o = UUID.randomUUID().toString();
        placed(o, "b_r", "r", "GRADA-E", List.of("GRADA-E:A01"), 1);
        held(o, "GRADA-E", 1, 2, ch("GRADA-E:A01", "HELD", 1));
        doReset();
        given().get("/api/buyers/b_r/orders").then().body("$", empty());
        given().get("/api/orders/" + o + "/timeline").then().body("$", empty());
        given().get("/api/stats").then().body("held", equalTo(1));
    }

    @Transactional
    void doReset() {
        reset.reset("r1");
    }

    @Test
    void endToEndThroughKafka() {
        publish(SaleOpened.newBuilder().setMeta(EventMetas.of("test", C)).setConcertId(C).build());
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                given().get("/api/stats").then().body("saleStatus", equalTo("OPEN")));
    }

    @Transactional
    void publish(SaleOpened e) {
        outbox.add(Topics.SALE_EVENTS, C, e);
    }
}
