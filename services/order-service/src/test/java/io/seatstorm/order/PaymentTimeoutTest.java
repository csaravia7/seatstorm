package io.seatstorm.order;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** An order stuck in PAYING (payment-sim down/paused) is cancelled by the timeout job and its seats are released. */
@QuarkusTest
class PaymentTimeoutTest {
    @Inject Fx fx;
    @Inject PaymentTimeoutJob.Worker worker;

    static final String SEC = "PLATEA-C";

    @BeforeEach
    void open() {
        fx.openSale();
    }

    private String payingOrder(String stuckFor) {
        String id = fx.createOk(Fx.buyer(), SEC, SEC + ":B0" + (1 + Fx.SEQ.incrementAndGet() % 9));
        fx.setStatus(id, "PAYING");
        fx.exec("update orders set held_until = now() + interval '120 seconds', updated_at = now() - interval '" + stuckFor
                + "' where order_id = '" + id + "'");
        return id;
    }

    @Test
    void stuckPaymentIsCancelledAndSeatsReleased() {
        String id = payingOrder("60 seconds");
        int n = worker.expireOnce(Instant.now().minusSeconds(30));
        assertTrue(n >= 1);
        assertEquals("CANCELLED", fx.status(id));
        assertEquals("PAYMENT_FAILED", fx.db("select reason from orders where order_id = '" + id + "'"));
        assertTrue(fx.emittedTypes(id).contains("ReleaseSeats"), "ReleaseSeats must be emitted: " + fx.emittedTypes(id));
    }

    @Test
    void recentPaymentIsLeftAlone() {
        String id = payingOrder("2 seconds");
        worker.expireOnce(Instant.now().minusSeconds(30));
        assertEquals("PAYING", fx.status(id));
    }

    @Test
    void secondRunIsANoOp() {
        String id = payingOrder("60 seconds");
        worker.expireOnce(Instant.now().minusSeconds(30));
        int before = fx.emitted(id).size();
        worker.expireOnce(Instant.now().minusSeconds(30));
        assertEquals(before, fx.emitted(id).size());
    }
}
