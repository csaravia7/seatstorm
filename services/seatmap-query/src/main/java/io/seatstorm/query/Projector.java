package io.seatstorm.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.seatstorm.contracts.EventMeta;
import io.seatstorm.contracts.OrderConfirmed;
import io.seatstorm.contracts.OrderPlaced;
import io.seatstorm.contracts.OrderStatusChanged;
import io.seatstorm.contracts.SaleClosed;
import io.seatstorm.contracts.SaleOpened;
import io.seatstorm.contracts.SeatChange;
import io.seatstorm.contracts.SeatsConfirmFailed;
import io.seatstorm.contracts.SeatsHeld;
import io.seatstorm.contracts.SeatsHoldExtended;
import io.seatstorm.contracts.SeatsRejected;
import io.seatstorm.contracts.SeatsReleased;
import io.seatstorm.contracts.SeatsSold;
import io.seatstorm.contracts.TicketIssued;
import io.seatstorm.contracts.VenueLayout;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SQL projections of domain events into the read model. Every method must run inside the caller's
 * transaction (the consumers are {@code @Transactional}).
 */
@ApplicationScoped
public class Projector {

    @Inject
    AgroalDataSource ds;

    @Inject
    ObjectMapper json;

    // ---- seat.events

    public void seatChanges(String concertId, List<SeatChange> changes) {
        run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "update seat_view set state = ?, version = ?, updated_at = now() "
                            + "where concert_id = ? and seat_id = ? and version < ?")) {
                for (SeatChange ch : changes) {
                    ps.setString(1, ch.getState());
                    ps.setLong(2, ch.getVersion());
                    ps.setString(3, concertId);
                    ps.setString(4, ch.getSeatId());
                    ps.setLong(5, ch.getVersion());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    public void onSeatEvent(Object event) {
        switch (event) {
            case SeatsHeld e -> {
                seatChanges(e.getConcertId(), e.getChanges());
                timeline(e.getOrderId(), "SeatsHeld", e.getMeta(), detail(
                        "sectionId", e.getSectionId(), "seatIds", ids(e.getChanges()), "heldUntilMs", e.getHeldUntilMs()));
            }
            case SeatsSold e -> {
                seatChanges(e.getConcertId(), e.getChanges());
                timeline(e.getOrderId(), "SeatsSold", e.getMeta(), detail(
                        "sectionId", e.getSectionId(), "seatIds", ids(e.getChanges())));
            }
            case SeatsReleased e -> {
                seatChanges(e.getConcertId(), e.getChanges());
                timeline(e.getOrderId(), "SeatsReleased", e.getMeta(), detail(
                        "sectionId", e.getSectionId(), "reason", e.getReason(), "seatIds", ids(e.getChanges())));
            }
            case SeatsRejected e -> timeline(e.getOrderId(), "SeatsRejected", e.getMeta(), detail(
                    "sectionId", e.getSectionId(), "reason", e.getReason(), "takenSeatIds", e.getTakenSeatIds()));
            case SeatsHoldExtended e -> timeline(e.getOrderId(), "SeatsHoldExtended", e.getMeta(), detail(
                    "sectionId", e.getSectionId(), "heldUntilMs", e.getHeldUntilMs()));
            case SeatsConfirmFailed e -> timeline(e.getOrderId(), "SeatsConfirmFailed", e.getMeta(), detail(
                    "sectionId", e.getSectionId(), "reason", e.getReason()));
            default -> { }
        }
    }

    private static List<String> ids(List<SeatChange> changes) {
        return changes.stream().map(SeatChange::getSeatId).toList();
    }

    // ---- order.events

    public void onOrderEvent(Object event) {
        switch (event) {
            case OrderPlaced e -> orderPlaced(e);
            case OrderStatusChanged e -> orderStatus(e);
            case OrderConfirmed e -> orderConfirmed(e);
            default -> { }
        }
    }

    void orderPlaced(OrderPlaced e) {
        long at = e.getMeta().getOccurredAtMs();
        run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into order_view (order_id, buyer_id, nickname, concert_id, section_id, seat_ids, total_cents, "
                            + "status, placed_at, last_event_ms) values (?, ?, ?, ?, ?, ?, ?, 'PLACED', ?, ?) "
                            + "on conflict (order_id) do update set nickname = excluded.nickname, "
                            + "section_id = excluded.section_id, seat_ids = excluded.seat_ids, "
                            + "total_cents = excluded.total_cents, placed_at = excluded.placed_at, updated_at = now()")) {
                ps.setObject(1, UUID.fromString(e.getOrderId()));
                ps.setString(2, e.getBuyerId());
                ps.setString(3, e.getNickname());
                ps.setString(4, e.getConcertId());
                ps.setString(5, e.getSectionId());
                ps.setArray(6, textArray(c, e.getSeatIds()));
                ps.setInt(7, e.getTotalCents());
                ps.setTimestamp(8, ts(at));
                ps.setLong(9, at);
                ps.executeUpdate();
            }
        });
        timeline(e.getOrderId(), "OrderPlaced", e.getMeta(), detail(
                "sectionId", e.getSectionId(), "seatIds", e.getSeatIds(), "totalCents", e.getTotalCents()));
    }

    void orderStatus(OrderStatusChanged e) {
        long at = e.getMeta().getOccurredAtMs();
        run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "update order_view set status = ?, reason = ?, held_until = ?, last_event_ms = ?, updated_at = now() "
                            + "where order_id = ? and last_event_ms <= ?")) {
                ps.setString(1, e.getStatus());
                ps.setString(2, e.getReason());
                ps.setTimestamp(3, e.getHeldUntilMs() == null ? null : ts(e.getHeldUntilMs()));
                ps.setLong(4, at);
                ps.setObject(5, UUID.fromString(e.getOrderId()));
                ps.setLong(6, at);
                ps.executeUpdate();
            }
        });
        timeline(e.getOrderId(), "OrderStatusChanged", e.getMeta(), detail(
                "status", e.getStatus(), "reason", e.getReason(), "heldUntilMs", e.getHeldUntilMs()));
    }

    void orderConfirmed(OrderConfirmed e) {
        long at = e.getMeta().getOccurredAtMs();
        run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into order_view (order_id, buyer_id, nickname, concert_id, section_id, seat_ids, total_cents, "
                            + "status, placed_at, confirmed_at, last_event_ms) "
                            + "values (?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?) "
                            + "on conflict (order_id) do update set confirmed_at = excluded.confirmed_at, "
                            + "placed_at = excluded.placed_at, nickname = excluded.nickname, "
                            + "section_id = excluded.section_id, seat_ids = excluded.seat_ids, "
                            + "total_cents = excluded.total_cents, "
                            + "status = case when order_view.last_event_ms <= excluded.last_event_ms "
                            + "then 'CONFIRMED' else order_view.status end, "
                            + "reason = case when order_view.last_event_ms <= excluded.last_event_ms "
                            + "then null else order_view.reason end, "
                            + "held_until = case when order_view.last_event_ms <= excluded.last_event_ms "
                            + "then null else order_view.held_until end, "
                            + "last_event_ms = greatest(order_view.last_event_ms, excluded.last_event_ms), "
                            + "updated_at = now()")) {
                ps.setObject(1, UUID.fromString(e.getOrderId()));
                ps.setString(2, e.getBuyerId());
                ps.setString(3, e.getNickname());
                ps.setString(4, e.getConcertId());
                ps.setString(5, e.getSectionId());
                ps.setArray(6, textArray(c, e.getSeatIds()));
                ps.setInt(7, e.getTotalCents());
                ps.setTimestamp(8, ts(e.getPlacedAtMs()));
                ps.setTimestamp(9, ts(at));
                ps.setLong(10, at);
                ps.executeUpdate();
            }
        });
        timeline(e.getOrderId(), "OrderConfirmed", e.getMeta(), detail(
                "sectionId", e.getSectionId(), "seatIds", e.getSeatIds(), "totalCents", e.getTotalCents()));
    }

    // ---- ticket.events

    public void onTicketEvent(Object event) {
        if (!(event instanceof TicketIssued e)) {
            return;
        }
        run(c -> {
            // Stub row when the ticket overtakes the order events (different topics); OrderPlaced/Confirmed fill the rest.
            String section = VenueLayout.sectionOf(e.getSeatIds().get(0));
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into order_view (order_id, buyer_id, nickname, concert_id, section_id, seat_ids, total_cents, "
                            + "status, placed_at, ticket_id, ticket_qr) "
                            + "values (?, ?, '', ?, ?, ?, 0, 'CONFIRMING', ?, ?, ?) "
                            + "on conflict (order_id) do update set ticket_id = excluded.ticket_id, "
                            + "ticket_qr = excluded.ticket_qr, updated_at = now()")) {
                ps.setObject(1, UUID.fromString(e.getOrderId()));
                ps.setString(2, e.getBuyerId());
                ps.setString(3, e.getConcertId());
                ps.setString(4, section);
                ps.setArray(5, textArray(c, e.getSeatIds()));
                ps.setTimestamp(6, ts(e.getMeta().getOccurredAtMs()));
                ps.setObject(7, UUID.fromString(e.getTicketId()));
                ps.setString(8, e.getQrPayload());
                ps.executeUpdate();
            }
        });
        timeline(e.getOrderId(), "TicketIssued", e.getMeta(), detail(
                "ticketId", e.getTicketId(), "seatIds", e.getSeatIds()));
    }

    // ---- sale.events

    public void onSaleEvent(Object event) {
        String concert;
        String status;
        if (event instanceof SaleOpened e) {
            concert = e.getConcertId();
            status = "OPEN";
        } else if (event instanceof SaleClosed e) {
            concert = e.getConcertId();
            status = "CLOSED";
        } else {
            return;
        }
        run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into sale_view (concert_id, status) values (?, ?) "
                            + "on conflict (concert_id) do update set status = excluded.status")) {
                ps.setString(1, concert);
                ps.setString(2, status);
                ps.executeUpdate();
            }
        });
    }

    // ---- reset

    public void resetOrders() {
        run(c -> {
            try (PreparedStatement ps = c.prepareStatement("truncate table order_view, order_timeline restart identity")) {
                ps.execute();
            }
        });
    }

    // ---- helpers

    void timeline(String orderId, String type, EventMeta meta, Map<String, Object> detail) {
        String detailJson;
        try {
            detailJson = json.writeValueAsString(detail);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "insert into order_timeline (order_id, event_type, detail, occurred_at) "
                            + "values (?, ?, cast(? as jsonb), ?)")) {
                ps.setObject(1, UUID.fromString(orderId));
                ps.setString(2, type);
                ps.setString(3, detailJson);
                ps.setTimestamp(4, ts(meta.getOccurredAtMs()));
                ps.executeUpdate();
            }
        });
    }

    static Map<String, Object> detail(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }

    static Timestamp ts(long ms) {
        return new Timestamp(ms);
    }

    static Array textArray(Connection c, List<String> values) throws SQLException {
        return c.createArrayOf("text", values.toArray());
    }

    @FunctionalInterface
    interface Sql {
        void run(Connection c) throws SQLException;
    }

    void run(Sql sql) {
        try (Connection c = ds.getConnection()) {
            sql.run(c);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
