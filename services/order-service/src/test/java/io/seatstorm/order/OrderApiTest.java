package io.seatstorm.order;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.seatstorm.contracts.*;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderApiTest {
    @Inject Fx fx;

    @BeforeEach
    void open() {
        fx.openSale();
    }

    @Test
    @Order(1)
    void createStoresOrderAndThreeOutboxEventsWithSectionPartition() {
        String buyer = Fx.buyer();
        var r = fx.create(buyer, "k1", "PALCO-2", "PALCO-2:A01", "PALCO-2:A02");
        r.then().statusCode(202).body("status", equalTo("PLACED")).body("orderId", notNullValue());
        String id = r.jsonPath().getString("orderId");
        assertEquals("PLACED", fx.status(id));
        assertEquals("30000", fx.db("select total_cents from orders where order_id = '" + id + "'"));
        assertEquals(List.of("OrderPlaced", "OrderStatusChanged", "HoldSeats"), fx.emittedTypes(id));
        var hold = fx.one(id, HoldSeats.class);
        assertEquals(60, hold.getHoldSeconds());
        assertEquals(List.of("PALCO-2:A01", "PALCO-2:A02"), hold.getSeatIds());
        assertEquals("5", fx.db("select partition_hint from outbox where event_type = 'HoldSeats' and payload_json like '%" + id + "%'"));
        assertEquals("PALCO-2", fx.db("select msg_key from outbox where event_type = 'HoldSeats' and payload_json like '%" + id + "%'"));
        assertEquals(30000, fx.one(id, OrderPlaced.class).getTotalCents());
        assertEquals("PLACED", fx.statuses(id).get(0).getStatus());
    }

    @Test
    @Order(2)
    void saleClosedIs409() {
        fx.closeSale();
        fx.create(Fx.buyer(), "k", "PLATEA-A", "PLATEA-A:A01").then().statusCode(409).body("code", equalTo("SALE_CLOSED"))
                .body("message", notNullValue()).body("details", notNullValue());
    }

    @Test
    @Order(3)
    void validation() {
        String b = Fx.buyer();
        fx.create(b, "v1", "PLATEA-A").then().statusCode(400).body("code", equalTo("VALIDATION_ERROR"));
        fx.create(b, "v2", "PLATEA-A", "PLATEA-A:A01", "PLATEA-A:A02", "PLATEA-A:A03", "PLATEA-A:A04", "PLATEA-A:A05")
                .then().statusCode(400).body("code", equalTo("VALIDATION_ERROR"));
        fx.create(b, "v3", "PLATEA-A", "PLATEA-A:A01", "PLATEA-B:A01").then().statusCode(400);
        fx.create(b, "v4", "PLATEA-A", "PLATEA-A:D01").then().statusCode(400);
        fx.create(b, "v5", "PLATEA-A", "PLATEA-A:A11").then().statusCode(400);
        fx.create(b, "v6", "NOPE", "NOPE:A01").then().statusCode(400);
        fx.create(b, "v7", "PLATEA-A", "PLATEA-A:A01", "PLATEA-A:A01").then().statusCode(400);
        given().contentType("application/json").header("X-Buyer-Id", b).body("{\"concertId\":\"" + Fx.CONCERT
                + "\",\"sectionId\":\"PLATEA-A\",\"seatIds\":[\"PLATEA-A:A01\"]}").post("/api/orders")
                .then().statusCode(400).body("code", equalTo("VALIDATION_ERROR")); // no Idempotency-Key
        given().contentType("application/json").body("{}").header("Idempotency-Key", "x").post("/api/orders")
                .then().statusCode(401).body("code", equalTo("NO_SESSION"));
        assertEquals("0", fx.db("select count(*) from orders where buyer_id = '" + b + "'"));
    }

    @Test
    @Order(4)
    void idempotentReplayReturnsSameOrderWithoutDuplicatingEvents() {
        String b = Fx.buyer();
        var first = fx.create(b, "same", "GRADA-N", "GRADA-N:B01");
        first.then().statusCode(202).header("Idempotent-Replay", nullValue());
        String id = first.jsonPath().getString("orderId");
        var again = fx.create(b, "same", "GRADA-N", "GRADA-N:B01");
        again.then().statusCode(200).header("Idempotent-Replay", "true").body("orderId", equalTo(id));
        assertEquals(3, fx.emitted(id).size());
        assertEquals("1", fx.db("select count(*) from orders where buyer_id = '" + b + "'"));
    }

    @Test
    @Order(5)
    void concurrentSameKeyYieldsOneOrder() throws Exception {
        String b = Fx.buyer();
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<Future<io.restassured.response.Response>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fs.add(ex.submit(() -> fx.create(b, "race", "GRADA-S", "GRADA-S:A01")));
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        int created = 0;
        for (var f : fs) {
            var r = f.get(30, TimeUnit.SECONDS);
            assertTrue(r.statusCode() == 202 || r.statusCode() == 200, "status " + r.statusCode());
            if (r.statusCode() == 202) created++;
            ids.add(r.jsonPath().getString("orderId"));
        }
        ex.shutdown();
        assertEquals(1, ids.size());
        assertEquals(1, created);
        assertEquals(3, fx.emitted(ids.iterator().next()).size());
    }

    @Test
    @Order(6)
    void secondOrderWithDifferentKeyWhileActiveIs409_thenAllowedAfterRejection() {
        String b = Fx.buyer();
        String id = fx.createOk(b, "GRADA-E", "GRADA-E:A01");
        fx.create(b, "other-key", "GRADA-E", "GRADA-E:A02").then().statusCode(409).body("code", equalTo("ACTIVE_ORDER_EXISTS"));
        fx.deliver(Fx.seatsRejected(id, b, "GRADA-E"));
        assertEquals("REJECTED", fx.status(id));
        fx.create(b, "third-key", "GRADA-E", "GRADA-E:A02").then().statusCode(202);
    }

    @Test
    @Order(7)
    void payRules() {
        String b = Fx.buyer();
        String id = fx.createOk(b, "GRADA-O", "GRADA-O:A01");
        fx.pay(b, id).then().statusCode(409).body("code", equalTo("INVALID_STATE")); // PLACED
        long until = Instant.now().plusSeconds(60).toEpochMilli();
        fx.deliver(Fx.seatsHeld(id, b, "GRADA-O", until));
        fx.pay(Fx.buyer(), id).then().statusCode(403).body("code", equalTo("NOT_YOUR_ORDER"));
        fx.pay(b, UUID.randomUUID().toString()).then().statusCode(404).body("code", equalTo("NOT_FOUND"));
        fx.pay(b, id).then().statusCode(202).body("status", equalTo("PAYING"));
        assertEquals(List.of("OrderPlaced", "OrderStatusChanged", "HoldSeats", "OrderStatusChanged", "RequestPayment",
                "ExtendHold", "OrderStatusChanged"), fx.emittedTypes(id));
        assertEquals(120, fx.one(id, ExtendHold.class).getExtraSeconds());
        assertEquals(4500, fx.one(id, RequestPayment.class).getAmountCents());
        assertEquals("CARD_SIM", fx.one(id, RequestPayment.class).getMethod());
        int before = fx.emitted(id).size();
        fx.pay(b, id).then().statusCode(409).body("code", equalTo("INVALID_STATE")); // double pay
        assertEquals(before, fx.emitted(id).size());
    }

    @Test
    @Order(8)
    void payWithExpiredHoldIs409HoldExpired() {
        String b = Fx.buyer();
        String id = fx.createOk(b, "GRADA-N", "GRADA-N:C01");
        fx.deliver(Fx.seatsHeld(id, b, "GRADA-N", Instant.now().minusSeconds(1).toEpochMilli()));
        assertEquals("HELD", fx.status(id));
        fx.pay(b, id).then().statusCode(409).body("code", equalTo("HOLD_EXPIRED"));
        assertEquals("HELD", fx.status(id));
        assertFalse(fx.emittedTypes(id).contains("RequestPayment"));
    }

    @Test
    @Order(9)
    void cancelOnlyInHeld() {
        String b = Fx.buyer();
        String id = fx.createOk(b, "GRADA-S", "GRADA-S:C01");
        fx.cancel(b, id).then().statusCode(409).body("code", equalTo("INVALID_STATE")); // PLACED
        fx.deliver(Fx.seatsHeld(id, b, "GRADA-S", Instant.now().plusSeconds(60).toEpochMilli()));
        fx.cancel(Fx.buyer(), id).then().statusCode(403);
        fx.cancel(b, id).then().statusCode(202).body("status", equalTo("CANCELLED"));
        assertEquals("CANCELLED", fx.status(id));
        assertEquals("USER_CANCELLED", fx.db("select reason from orders where order_id = '" + id + "'"));
        var rel = fx.one(id, ReleaseSeats.class);
        assertEquals("USER_CANCELLED", rel.getReason());
        fx.cancel(b, id).then().statusCode(409);
    }

    @Test
    @Order(10)
    void getReturnsCurrentStateAndHeldUntil() {
        String b = Fx.buyer();
        String id = fx.createOk(b, "PALCO-1", "PALCO-1:B05");
        fx.get(b, id).then().statusCode(200).body("status", equalTo("PLACED")).body("heldUntilMs", nullValue())
                .body("sectionId", equalTo("PALCO-1")).body("seatIds", contains("PALCO-1:B05"))
                .body("totalCents", equalTo(15000)).body("reason", nullValue());
        long until = Instant.now().plusSeconds(60).toEpochMilli();
        fx.deliver(Fx.seatsHeld(id, b, "PALCO-1", until));
        fx.get(b, id).then().body("status", equalTo("HELD")).body("heldUntilMs", equalTo(until));
        fx.get(Fx.buyer(), id).then().statusCode(403);
        fx.get(b, UUID.randomUUID().toString()).then().statusCode(404);
    }

    @Test
    @Order(11)
    void resetTruncatesOrdersButKeepsSaleState() {
        String b = Fx.buyer();
        fx.createOk(b, "PALCO-3", "PALCO-3:A01");
        assertNotEquals("0", fx.db("select count(*) from orders"));
        fx.reset();
        assertEquals("0", fx.db("select count(*) from orders"));
        assertEquals("OPEN", fx.db("select status from sale_state"));
    }
}
