package io.seatstorm.order;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.seatstorm.contracts.*;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One test per row of the reaction table (spec 7.3) plus "ignored in every other state". Events go straight to the handlers. */
@QuarkusTest
class SagaReactionsTest {
    @Inject Fx fx;

    static final String SEC = "PLATEA-B";

    @BeforeEach
    void open() {
        fx.openSale();
    }

    /** Creates an order and forces it into the given state (columns that the state needs are filled). */
    String order(String buyer, String state) {
        String id = fx.createOk(buyer, SEC, SEC + ":A0" + (1 + Fx.SEQ.incrementAndGet() % 9));
        fx.setStatus(id, state);
        if (!state.equals("PLACED")) {
            fx.exec("update orders set held_until = now() + interval '60 seconds' where order_id = '" + id + "'");
        }
        return id;
    }

    String order(String state) {
        return order(Fx.buyer(), state);
    }

    private String st(String id) { return fx.status(id); }

    private String reason(String id) { return fx.db("select reason from orders where order_id = '" + id + "'"); }

    private int emittedCount(String id) { return fx.emitted(id).size(); }

    private static final List<String> ALL = List.of("PLACED", "HELD", "PAYING", "CONFIRMING", "REFUNDING",
            "CONFIRMED", "REJECTED", "CANCELLED", "EXPIRED");

    // ---- SeatsHeld
    @Test
    void seatsHeldInPlacedMovesToHeldWithHeldUntil() {
        String b = Fx.buyer();
        String id = order(b, "PLACED");
        long until = Instant.now().plusSeconds(60).toEpochMilli();
        fx.deliver(Fx.seatsHeld(id, b, SEC, until));
        assertEquals("HELD", st(id));
        assertEquals(until, fx.get(b, id).jsonPath().getLong("heldUntilMs"));
        var last = fx.statuses(id).get(fx.statuses(id).size() - 1);
        assertEquals("HELD", last.getStatus());
        assertEquals(until, last.getHeldUntilMs());
    }

    @Test
    void seatsHeldIgnoredInOtherStates() {
        for (String s : ALL) {
            if (s.equals("PLACED")) continue;
            String b = Fx.buyer();
            String id = order(b, s);
            int n = emittedCount(id);
            fx.deliver(Fx.seatsHeld(id, b, SEC, Instant.now().plusSeconds(60).toEpochMilli()));
            assertEquals(s, st(id), s);
            assertEquals(n, emittedCount(id), s);
        }
    }

    // ---- SeatsRejected
    @Test
    void seatsRejectedInPlacedMovesToRejected() {
        String b = Fx.buyer();
        String id = order(b, "PLACED");
        fx.deliver(Fx.seatsRejected(id, b, SEC));
        assertEquals("REJECTED", st(id));
        assertEquals("SEAT_TAKEN", reason(id));
        assertEquals("REJECTED", fx.statuses(id).get(fx.statuses(id).size() - 1).getStatus());
    }

    @Test
    void seatsRejectedIgnoredInOtherStates() {
        for (String s : ALL) {
            if (s.equals("PLACED")) continue;
            String b = Fx.buyer();
            String id = order(b, s);
            int n = emittedCount(id);
            fx.deliver(Fx.seatsRejected(id, b, SEC));
            assertEquals(s, st(id), s);
            assertEquals(n, emittedCount(id), s);
        }
    }

    // ---- SeatsHoldExtended
    @Test
    void seatsHoldExtendedUpdatesHeldUntilInPayingAndHeldOnly() {
        long until = Instant.now().plusSeconds(180).toEpochMilli();
        for (String s : ALL) {
            String id = order(s);
            int n = emittedCount(id);
            fx.deliver(Fx.seatsExtended(id, SEC, until));
            boolean applies = s.equals("PAYING") || s.equals("HELD");
            Number hu = fx.get(fx.db("select buyer_id from orders where order_id = '" + id + "'"), id).jsonPath().get("heldUntilMs");
            long got = hu == null ? 0 : hu.longValue();
            if (applies) assertEquals(until, got, s); else assertNotEquals(until, got, s);
            assertEquals(s, st(id));
            assertEquals(n, emittedCount(id), s);
        }
    }

    // ---- SeatsReleased
    @Test
    void seatsReleasedHoldExpiredInHeldMovesToExpired() {
        String id = order("HELD");
        fx.deliver(Fx.seatsReleased(id, SEC, "HOLD_EXPIRED"));
        assertEquals("EXPIRED", st(id));
        assertEquals("HOLD_EXPIRED", reason(id));
        assertEquals("EXPIRED", fx.statuses(id).get(fx.statuses(id).size() - 1).getStatus());
    }

    @Test
    void seatsReleasedIgnoredInOtherStatesAndForOtherReasons() {
        for (String s : ALL) {
            if (s.equals("HELD")) continue;
            String id = order(s);
            int n = emittedCount(id);
            fx.deliver(Fx.seatsReleased(id, SEC, "HOLD_EXPIRED"));
            assertEquals(s, st(id), s);
            assertEquals(n, emittedCount(id), s);
        }
        String id = order("HELD");
        fx.deliver(Fx.seatsReleased(id, SEC, "RESET"));
        assertEquals("HELD", st(id));
    }

    // ---- PaymentAuthorized
    @Test
    void paymentAuthorizedInPayingMovesToConfirmingAndEmitsConfirmSeats() {
        String id = order("PAYING");
        fx.deliver(Fx.authorized(id, "pay-1", 9000));
        assertEquals("CONFIRMING", st(id));
        assertEquals("pay-1", fx.db("select payment_id from orders where order_id = '" + id + "'"));
        assertEquals("ConfirmSeats", last(id));
        assertEquals(SEC, fx.one(id, ConfirmSeats.class).getSectionId());
    }

    @Test
    void paymentAuthorizedInOtherStateEmitsRefund() {
        for (String s : ALL) {
            if (s.equals("PAYING")) continue;
            String id = order(s);
            fx.deliver(Fx.authorized(id, "late-" + s, 9000));
            assertEquals(s, st(id), s);
            var refund = fx.one(id, RefundPayment.class);
            assertEquals("INVALID_STATE", refund.getReason(), s);
            assertEquals("late-" + s, refund.getPaymentId());
            assertEquals(9000, refund.getAmountCents());
        }
    }

    @Test
    void duplicateAuthorizationOfAcceptedPaymentIsNotRefunded() {
        String id = order("PAYING");
        fx.deliver(Fx.authorized(id, "pay-dup", 9000));
        fx.deliver(Fx.authorized(id, "pay-dup", 9000)); // re-emitted by payment-sim with a new eventId
        assertEquals("CONFIRMING", st(id));
        assertFalse(fx.emittedTypes(id).contains("RefundPayment"));
    }

    // ---- PaymentFailed
    @Test
    void paymentFailedInPayingCancelsAndReleasesSeats() {
        String id = order("PAYING");
        fx.deliver(Fx.failed(id));
        assertEquals("CANCELLED", st(id));
        assertEquals("PAYMENT_FAILED", reason(id));
        assertEquals("PAYMENT_FAILED", fx.one(id, ReleaseSeats.class).getReason());
        assertEquals("CANCELLED", fx.statuses(id).get(fx.statuses(id).size() - 1).getStatus());
    }

    @Test
    void paymentFailedIgnoredInOtherStates() {
        for (String s : ALL) {
            if (s.equals("PAYING")) continue;
            String id = order(s);
            int n = emittedCount(id);
            fx.deliver(Fx.failed(id));
            assertEquals(s, st(id), s);
            assertEquals(n, emittedCount(id), s);
        }
    }

    // ---- SeatsSold
    @Test
    void seatsSoldInConfirmingConfirms() {
        String b = Fx.buyer();
        String id = order(b, "CONFIRMING");
        fx.deliver(Fx.seatsSold(id, b, SEC));
        assertEquals("CONFIRMED", st(id));
        var c = fx.one(id, OrderConfirmed.class);
        assertEquals(b, c.getBuyerId());
        assertTrue(c.getPlacedAtMs() > 0 && c.getPlacedAtMs() <= System.currentTimeMillis());
        assertEquals(1, c.getSeatIds().size());
        assertEquals("CONFIRMED", fx.statuses(id).get(fx.statuses(id).size() - 1).getStatus());
    }

    @Test
    void seatsSoldIgnoredInOtherStates() {
        for (String s : ALL) {
            if (s.equals("CONFIRMING")) continue;
            String b = Fx.buyer();
            String id = order(b, s);
            int n = emittedCount(id);
            fx.deliver(Fx.seatsSold(id, b, SEC));
            assertEquals(s, st(id), s);
            assertEquals(n, emittedCount(id), s);
        }
    }

    // ---- SeatsConfirmFailed (R6)
    @Test
    void seatsConfirmFailedInConfirmingRefunds() {
        String id = order("CONFIRMING");
        fx.exec("update orders set payment_id = 'pay-9' where order_id = '" + id + "'");
        fx.deliver(Fx.seatsConfirmFailed(id, SEC));
        assertEquals("REFUNDING", st(id));
        var r = fx.one(id, RefundPayment.class);
        assertEquals("HOLD_EXPIRED", r.getReason());
        assertEquals("pay-9", r.getPaymentId());
        assertEquals(9000, r.getAmountCents());
    }

    @Test
    void seatsConfirmFailedIgnoredInOtherStates() {
        for (String s : ALL) {
            if (s.equals("CONFIRMING")) continue;
            String id = order(s);
            int n = emittedCount(id);
            fx.deliver(Fx.seatsConfirmFailed(id, SEC));
            assertEquals(s, st(id), s);
            assertEquals(n, emittedCount(id), s);
        }
    }

    // ---- PaymentRefunded
    @Test
    void paymentRefundedInRefundingCancelsWithHoldExpired() {
        String id = order("REFUNDING");
        fx.deliver(Fx.refunded(id, "p"));
        assertEquals("CANCELLED", st(id));
        assertEquals("HOLD_EXPIRED", reason(id));
        var last = fx.statuses(id).get(fx.statuses(id).size() - 1);
        assertEquals("CANCELLED", last.getStatus());
        assertEquals("HOLD_EXPIRED", last.getReason());
    }

    @Test
    void paymentRefundedIgnoredInOtherStates() {
        for (String s : ALL) {
            if (s.equals("REFUNDING")) continue;
            String id = order(s);
            int n = emittedCount(id);
            fx.deliver(Fx.refunded(id, "p"));
            assertEquals(s, st(id), s);
            assertEquals(n, emittedCount(id), s);
        }
    }

    // ---- sale events
    @Test
    void saleEventsUpsertSaleState() {
        fx.deliver(SaleClosed.newBuilder().setMeta(EventMetas.of("event-catalog", Fx.CONCERT)).setConcertId(Fx.CONCERT).build());
        assertEquals("CLOSED", fx.db("select status from sale_state where concert_id = '" + Fx.CONCERT + "'"));
        fx.deliver(SaleOpened.newBuilder().setMeta(EventMetas.of("event-catalog", Fx.CONCERT)).setConcertId(Fx.CONCERT).build());
        assertEquals("OPEN", fx.db("select status from sale_state where concert_id = '" + Fx.CONCERT + "'"));
    }

    // ---- misc
    @Test
    void redeliveredEventIsDeduplicatedByInbox() {
        String b = Fx.buyer();
        String id = order(b, "PLACED");
        var held = Fx.seatsHeld(id, b, SEC, Instant.now().plusSeconds(60).toEpochMilli());
        fx.deliver(held);
        int n = emittedCount(id);
        fx.deliver(held); // same eventId
        assertEquals(n, emittedCount(id));
    }

    @Test
    void eventForUnknownOrderIsIgnored() {
        String ghost = UUID.randomUUID().toString();
        fx.deliver(Fx.seatsHeld(ghost, "nobody", SEC, 1L));
        fx.deliver(Fx.authorized(ghost, "p", 1));
        assertEquals(0, fx.emitted(ghost).size());
    }

    private String last(String id) {
        List<String> t = fx.emittedTypes(id);
        return t.get(t.size() - 1);
    }
}
