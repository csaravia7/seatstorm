package io.seatstorm.ticketing;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.seatstorm.contracts.*;
import io.seatstorm.platform.outbox.Outbox;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TicketingTest {
    @Inject Outbox outbox;
    @Inject EntityManager em;
    @Inject EventRegistry registry;
    @Inject TicketResetHandler resetHandler;
    @Inject TicketService tickets;

    String db(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<?> l = em.createNativeQuery(sql).getResultList();
            return l.isEmpty() || l.get(0) == null ? null : String.valueOf(l.get(0));
        });
    }

    static OrderConfirmed confirmed(String orderId) {
        return OrderConfirmed.newBuilder().setMeta(EventMetas.of("order-service", orderId)).setOrderId(orderId)
                .setBuyerId("b_1").setNickname("nick").setConcertId(VenueLayout.CONCERT_ID).setSectionId("PLATEA-A")
                .setSeatIds(List.of("PLATEA-A:B07", "PLATEA-A:B08")).setTotalCents(18000).setPlacedAtMs(1L).build();
    }

    void send(String orderId, org.apache.avro.specific.SpecificRecord e) {
        QuarkusTransaction.requiringNew().run(() -> outbox.add(Topics.ORDER_EVENTS, orderId, null, e));
    }

    @Test
    void orderConfirmedIssuesOneSignedTicketAndRepeatsAreIdempotent() {
        String orderId = UUID.randomUUID().toString();
        var first = confirmed(orderId);
        send(orderId, first);
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(
                () -> "1".equals(db("select count(*) from ticket where order_id = '" + orderId + "'")));

        String qr = db("select qr_payload from ticket where order_id = '" + orderId + "'");
        String ticketId = db("select ticket_id from ticket where order_id = '" + orderId + "'");
        assertTrue(qr.startsWith("v1." + ticketId + "." + orderId + "."), qr);
        assertTrue(QrSigner.verify("test-ticket-secret", qr));
        assertFalse(QrSigner.verify("wrong", qr));
        assertEquals("PLATEA-A:B07,PLATEA-A:B08", db("select array_to_string(seat_ids, ',') from ticket where order_id = '" + orderId + "'"));

        // TicketIssued row in the outbox, key = orderId, with the same QR
        String json = db("select payload_json from outbox where event_type = 'TicketIssued' and msg_key = '" + orderId + "'");
        TicketIssued issued = (TicketIssued) registry.fromJson("TicketIssued", json);
        assertEquals(qr, issued.getQrPayload());
        assertEquals(ticketId, issued.getTicketId());
        assertEquals("b_1", issued.getBuyerId());
        assertEquals(List.of("PLATEA-A:B07", "PLATEA-A:B08"), issued.getSeatIds());
        assertEquals("ticket.events", db("select topic from outbox where event_type = 'TicketIssued' and msg_key = '" + orderId + "'"));

        // same eventId (inbox) and a new eventId for the same order (unique order_id): still one ticket, one event
        tickets.handle(first); // same eventId straight to the handler (outbox rows are unique per eventId)
        send(orderId, confirmed(orderId));
        // a follow-up event on the same topic acts as a barrier: once it is processed the two above were too
        String barrier = UUID.randomUUID().toString();
        send(barrier, confirmed(barrier));
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(
                () -> "1".equals(db("select count(*) from ticket where order_id = '" + barrier + "'")));
        assertEquals("1", db("select count(*) from ticket where order_id = '" + orderId + "'"));
        assertEquals("1", db("select count(*) from outbox where event_type = 'TicketIssued' and msg_key = '" + orderId + "'"));
    }

    @Test
    void otherOrderEventsAreIgnored() {
        String orderId = UUID.randomUUID().toString();
        send(orderId, OrderStatusChanged.newBuilder().setMeta(EventMetas.of("order-service", orderId))
                .setOrderId(orderId).setBuyerId("b").setStatus("HELD").build());
        String barrier = UUID.randomUUID().toString();
        send(barrier, confirmed(barrier));
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(
                () -> "1".equals(db("select count(*) from ticket where order_id = '" + barrier + "'")));
        assertEquals("0", db("select count(*) from ticket where order_id = '" + orderId + "'"));
    }

    @Test
    void resetEmptiesTickets() {
        String orderId = UUID.randomUUID().toString();
        send(orderId, confirmed(orderId));
        Awaitility.await().atMost(Duration.ofSeconds(60)).until(
                () -> "1".equals(db("select count(*) from ticket where order_id = '" + orderId + "'")));
        QuarkusTransaction.requiringNew().run(() -> resetHandler.reset("r"));
        assertEquals("0", db("select count(*) from ticket"));
    }
}
