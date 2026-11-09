package io.seatstorm.ticketing;

import io.quarkus.logging.Log;
import io.seatstorm.contracts.*;
import io.seatstorm.platform.inbox.Inbox;
import io.seatstorm.platform.outbox.Outbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.avro.specific.SpecificRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class TicketService {
    public static final String PRODUCER = "ticketing";

    @Inject
    Inbox inbox;

    @Inject
    Outbox outbox;

    @Inject
    EntityManager em;

    @ConfigProperty(name = "seatstorm.ticket.secret")
    String secret;

    /** Inbox check, ticket insert and TicketIssued outbox row in one transaction. */
    @Transactional
    public void handle(SpecificRecord v) {
        if (!(v instanceof OrderConfirmed c)) {
            return; // OrderPlaced, OrderStatusChanged...: not ours (no inbox row needed)
        }
        if (!inbox.firstTime("ticketing.order-events", EventMetas.idOf(v))) {
            return;
        }
        issue(c);
    }

    void issue(OrderConfirmed c) {
        UUID ticketId = UUID.randomUUID();
        UUID orderId = UUID.fromString(c.getOrderId());
        String qr = QrSigner.sign(secret, ticketId.toString(), orderId.toString());
        // order_id UNIQUE makes this idempotent even when the same order is confirmed again with another eventId
        int rows = em.createNativeQuery("insert into ticket(ticket_id, order_id, buyer_id, concert_id, seat_ids, qr_payload, issued_at) "
                        + "values (:t, :o, :b, :c, cast(:s as text[]), :q, :at) on conflict (order_id) do nothing")
                .setParameter("t", ticketId).setParameter("o", orderId).setParameter("b", c.getBuyerId())
                .setParameter("c", c.getConcertId()).setParameter("s", pgArray(c.getSeatIds())).setParameter("q", qr)
                .setParameter("at", Instant.now()).executeUpdate();
        if (rows == 0) {
            Log.infof("Ticket for order %s already issued", orderId);
            return;
        }
        outbox.add(Topics.TICKET_EVENTS, c.getOrderId(), null, TicketIssued.newBuilder()
                .setMeta(EventMetas.of(PRODUCER, c.getOrderId(), c.getMeta().getEventId()))
                .setTicketId(ticketId.toString()).setOrderId(c.getOrderId()).setBuyerId(c.getBuyerId())
                .setConcertId(c.getConcertId()).setSeatIds(List.copyOf(c.getSeatIds())).setQrPayload(qr).build());
    }

    /** Postgres array literal; seat ids are [A-Z0-9:-] so no escaping is needed, but we quote defensively. */
    static String pgArray(List<String> items) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(items.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
