package io.seatstorm.order;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.seatstorm.contracts.*;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Whole saga over real Kafka + Apicurio. The test plays seat-inventory and payment-sim: it publishes their events
 * (through the outbox/relay, i.e. with the production serializer) and reads the commands order-service emitted.
 */
@QuarkusTest
class SagaFlowTest {
    @Inject Fx fx;

    @BeforeEach
    void open() {
        fx.openSale();
    }

    private static long in(int seconds) {
        return Instant.now().plusSeconds(seconds).toEpochMilli();
    }

    @Test
    void happyPath() {
        String b = Fx.buyer();
        String sec = "PLATEA-C";
        String id = fx.createOk(b, sec, sec + ":A01", sec + ":A02");
        assertEquals(List.of("OrderPlaced", "OrderStatusChanged", "HoldSeats"), fx.emittedTypes(id));

        fx.sendSeat(sec, Fx.seatsHeld(id, b, sec, in(60)));
        fx.awaitStatus(id, "HELD");

        fx.pay(b, id).then().statusCode(202);
        assertEquals("PAYING", fx.status(id));
        fx.sendSeat(sec, Fx.seatsExtended(id, sec, in(120)));
        fx.sendPayment(id, Fx.authorized(id, "pay-ok", 18000));
        fx.awaitStatus(id, "CONFIRMING");
        assertEquals(1, fx.emitted(id).stream().filter(r -> r instanceof ConfirmSeats).count());

        fx.sendSeat(sec, Fx.seatsSold(id, b, sec));
        fx.awaitStatus(id, "CONFIRMED");
        var confirmed = fx.one(id, OrderConfirmed.class);
        assertEquals(18000, confirmed.getTotalCents());
        assertEquals(List.of("PLACED", "HELD", "PAYING", "CONFIRMED"),
                fx.statuses(id).stream().map(OrderStatusChanged::getStatus).toList());

        // after the final state the buyer can buy again
        fx.create(b, "again", sec, sec + ":B01").then().statusCode(202);
    }

    @Test
    void seatsRejectedEndsRejected() {
        String b = Fx.buyer();
        String sec = "PLATEA-D";
        String id = fx.createOk(b, sec, sec + ":A01");
        fx.sendSeat(sec, Fx.seatsRejected(id, b, sec));
        fx.awaitStatus(id, "REJECTED");
        assertEquals("SEAT_TAKEN", fx.db("select reason from orders where order_id = '" + id + "'"));
        fx.create(b, "retry", sec, sec + ":A02").then().statusCode(202);
    }

    @Test
    void paymentFailedEndsCancelledAndReleasesSeats() {
        String b = Fx.buyer();
        String sec = "PALCO-4";
        String id = fx.createOk(b, sec, sec + ":C10");
        fx.sendSeat(sec, Fx.seatsHeld(id, b, sec, in(60)));
        fx.awaitStatus(id, "HELD");
        fx.pay(b, id).then().statusCode(202);
        fx.sendPayment(id, Fx.failed(id));
        fx.awaitStatus(id, "CANCELLED");
        assertEquals("PAYMENT_FAILED", fx.db("select reason from orders where order_id = '" + id + "'"));
        assertEquals("PAYMENT_FAILED", fx.one(id, ReleaseSeats.class).getReason());
    }

    @Test
    void holdExpiredDuringPaymentIsRefunded() {
        String b = Fx.buyer();
        String sec = "GRADA-N";
        String id = fx.createOk(b, sec, sec + ":B10");
        fx.sendSeat(sec, Fx.seatsHeld(id, b, sec, in(60)));
        fx.awaitStatus(id, "HELD");
        fx.pay(b, id).then().statusCode(202);
        fx.sendPayment(id, Fx.authorized(id, "pay-late", 4500));
        fx.awaitStatus(id, "CONFIRMING");
        fx.sendSeat(sec, Fx.seatsConfirmFailed(id, sec)); // seat-inventory: hold gone
        fx.awaitStatus(id, "REFUNDING");
        var refund = fx.one(id, RefundPayment.class);
        assertEquals("pay-late", refund.getPaymentId());
        assertEquals("HOLD_EXPIRED", refund.getReason());
        assertEquals(4500, refund.getAmountCents());
        fx.sendPayment(id, Fx.refunded(id, "pay-late"));
        fx.awaitStatus(id, "CANCELLED");
        assertEquals("HOLD_EXPIRED", fx.db("select reason from orders where order_id = '" + id + "'"));
    }

    @Test
    void holdExpiredBeforePayingEndsExpired() {
        String b = Fx.buyer();
        String sec = "GRADA-S";
        String id = fx.createOk(b, sec, sec + ":B10");
        fx.sendSeat(sec, Fx.seatsHeld(id, b, sec, in(60)));
        fx.awaitStatus(id, "HELD");
        fx.sendSeat(sec, Fx.seatsReleased(id, sec, "HOLD_EXPIRED"));
        fx.awaitStatus(id, "EXPIRED");
        fx.pay(b, id).then().statusCode(409);
    }

    @Test
    void commandsReachTheirTopicsWithExplicitPartition() {
        String b = Fx.buyer();
        String sec = "GRADA-O"; // index 11
        String id = fx.createOk(b, sec, sec + ":B09");
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).until(
                () -> "1".equals(fx.db("select count(*) from outbox where event_type = 'HoldSeats' and sent_at is not null and payload_json like '%" + id + "%'")));
    }
}
