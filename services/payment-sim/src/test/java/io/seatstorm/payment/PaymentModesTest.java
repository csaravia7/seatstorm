package io.seatstorm.payment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.seatstorm.contracts.*;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PaymentModesTest {
    static final String TOKEN = "change-me-admin";

    @Inject Fx fx;

    @AfterEach
    void restore() {
        mode(Map.of("mode", "OK"));
    }

    static io.restassured.response.Response mode(Map<String, Object> body) {
        return given().header("X-Admin-Token", TOKEN).contentType("application/json").body(body).put("/admin/mode");
    }

    @Test
    void okAuthorizesAndStoresPayment() {
        mode(Map.of("mode", "OK")).then().statusCode(200);
        String id = Fx.oid();
        fx.deliver(Fx.request(id, 9000));
        assertEquals("AUTHORIZED", fx.paymentStatus(id));
        var ev = fx.emitted(id);
        assertEquals(1, ev.size());
        var a = (PaymentAuthorized) ev.get(0);
        assertEquals(9000, a.getAmountCents());
        assertEquals(fx.db("select payment_id from payment where order_id = '" + id + "'"), a.getPaymentId());
    }

    @Test
    void flakyWithRate1AlwaysFailsAndRate0NeverFails() {
        mode(Map.of("mode", "FLAKY", "failureRate", 1.0)).then().statusCode(200).body("failureRate", equalTo(1.0f));
        for (int i = 0; i < 5; i++) {
            String id = Fx.oid();
            fx.deliver(Fx.request(id, 100));
            assertEquals("FAILED", fx.paymentStatus(id));
            var f = (PaymentFailed) fx.emitted(id).get(0);
            assertTrue(List.of("CARD_DECLINED", "INSUFFICIENT_FUNDS").contains(f.getReason()), f.getReason());
        }
        mode(Map.of("mode", "FLAKY", "failureRate", 0.0)).then().statusCode(200);
        for (int i = 0; i < 5; i++) {
            String id = Fx.oid();
            fx.deliver(Fx.request(id, 100));
            assertEquals("AUTHORIZED", fx.paymentStatus(id));
        }
    }

    @Test
    void slowTakesAtLeastLatency() {
        mode(Map.of("mode", "SLOW", "latencyMs", 2000)).then().statusCode(200).body("latencyMs", equalTo(2000));
        String id = Fx.oid();
        long t0 = System.nanoTime();
        fx.deliver(Fx.request(id, 100));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms >= 2000, "took " + ms);
        assertEquals("AUTHORIZED", fx.paymentStatus(id));
    }

    @Test
    void redeliveryReemitsStoredResultWithoutSecondPayment() {
        String id = Fx.oid();
        var first = Fx.request(id, 500);
        fx.deliver(first);
        fx.deliver(first); // same eventId: inbox drops it
        assertEquals(1, fx.emitted(id).size());
        fx.deliver(Fx.request(id, 500)); // new eventId, same order: re-emits
        assertEquals("1", fx.db("select count(*) from payment where order_id = '" + id + "'"));
        var ev = fx.emitted(id);
        assertEquals(2, ev.size());
        assertEquals(((PaymentAuthorized) ev.get(0)).getPaymentId(), ((PaymentAuthorized) ev.get(1)).getPaymentId());

        // a stored failure is re-emitted as a failure even if the mode is OK now
        mode(Map.of("mode", "FLAKY", "failureRate", 1.0));
        String bad = Fx.oid();
        fx.deliver(Fx.request(bad, 500));
        mode(Map.of("mode", "OK"));
        fx.deliver(Fx.request(bad, 500));
        var evs = fx.emitted(bad);
        assertEquals(2, evs.size());
        assertTrue(evs.stream().allMatch(e -> e instanceof PaymentFailed));
        assertEquals("1", fx.db("select count(*) from payment where order_id = '" + bad + "'"));
    }

    @Test
    void refundMarksRefundedAndIsIdempotent() {
        String id = Fx.oid();
        fx.deliver(Fx.request(id, 700));
        String pid = fx.db("select payment_id from payment where order_id = '" + id + "'");
        fx.deliver(Fx.refund(id, pid, 700));
        assertEquals("REFUNDED", fx.paymentStatus(id));
        var refunded = (PaymentRefunded) fx.emitted(id).get(1);
        assertEquals(pid, refunded.getPaymentId());
        fx.deliver(Fx.refund(id, pid, 700)); // repeated
        assertEquals("REFUNDED", fx.paymentStatus(id));
        assertEquals("1", fx.db("select count(*) from payment where order_id = '" + id + "'"));
        assertEquals(3, fx.emitted(id).size());
        assertTrue(fx.emitted(id).get(2) instanceof PaymentRefunded);
        // unknown payment still answers so the saga cannot hang
        String ghost = Fx.oid();
        fx.deliver(Fx.refund(ghost, "nope", 1));
        assertTrue(fx.emitted(ghost).get(0) instanceof PaymentRefunded);
        assertNull(fx.paymentStatus(ghost));
    }

    @Test
    void adminValidationAndGet() {
        given().contentType("application/json").body(Map.of("mode", "OK")).put("/admin/mode")
                .then().statusCode(401).body("code", equalTo("BAD_ADMIN_TOKEN"));
        mode(Map.of("mode", "BROKEN")).then().statusCode(400).body("code", equalTo("VALIDATION_ERROR"));
        mode(Map.of("mode", "FLAKY", "failureRate", 1.5)).then().statusCode(400);
        mode(Map.of("mode", "FLAKY", "failureRate", -0.1)).then().statusCode(400);
        mode(Map.of("mode", "SLOW", "latencyMs", -1)).then().statusCode(400);
        mode(Map.of("mode", "SLOW", "latencyMs", 999999)).then().statusCode(400);
        given().header("X-Admin-Token", TOKEN).contentType("application/json").body("{}").put("/admin/mode").then().statusCode(400);
        mode(Map.of("mode", "FLAKY", "failureRate", 0.3, "latencyMs", 800)).then().statusCode(200);
        given().header("X-Admin-Token", TOKEN).get("/admin/mode").then().statusCode(200)
                .body("mode", equalTo("FLAKY")).body("failureRate", equalTo(0.3f)).body("latencyMs", equalTo(800));
    }

    @Test
    void resetEmptiesPaymentsAndRestoresOk() {
        mode(Map.of("mode", "SLOW", "latencyMs", 10, "failureRate", 0.9));
        String id = Fx.oid();
        fx.deliver(Fx.request(id, 1));
        assertNotEquals("0", fx.db("select count(*) from payment"));
        fx.reset();
        assertEquals("0", fx.db("select count(*) from payment"));
        given().header("X-Admin-Token", TOKEN).get("/admin/mode").then().body("mode", equalTo("OK"));
    }
}
