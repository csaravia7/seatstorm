package io.seatstorm.inventory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.sql.DataSource;

/**
 * Plain JDBC access to {@code seat}, always inside the caller's JTA transaction (the Agroal pool hands out the
 * transaction-bound connection, the same one Hibernate uses for the outbox/inbox rows).
 * All time comparisons use the database clock ({@code clock_timestamp()}).
 */
@ApplicationScoped
public class SeatDao {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String HELD = "HELD";
    public static final String SOLD = "SOLD";

    /** A seat row read under lock. {@code nowMs} is the database clock when the row was read. */
    public record Seat(String concertId, String seatId, String sectionId, String state, String orderId,
                       String buyerId, Long heldUntilMs, long version, long nowMs) {
        public boolean heldBy(String order) {
            return HELD.equals(state) && order.equals(orderId);
        }

        /** HELD by another order with a hold still in force, or SOLD (spec 7.2). */
        public boolean takenFor(String order) {
            if (SOLD.equals(state)) {
                return true;
            }
            return HELD.equals(state) && heldUntilMs > nowMs && !order.equals(orderId);
        }
    }

    private static final String COLS = "concert_id, seat_id, section_id, state, order_id::text, buyer_id, "
            + "(extract(epoch from held_until) * 1000)::bigint, version, "
            + "(extract(epoch from clock_timestamp()) * 1000)::bigint";

    @Inject
    DataSource ds;

    /** Locks the given seats ({@code FOR UPDATE}, ordered by seat_id so concurrent holds cannot deadlock). */
    public List<Seat> lockSeats(String concertId, Collection<String> seatIds) {
        return query("select " + COLS + " from seat where concert_id = ? and seat_id = ANY(?) "
                + "order by seat_id for update", (c, ps) -> {
            ps.setString(1, concertId);
            ps.setArray(2, c.createArrayOf("text", seatIds.toArray()));
        });
    }

    /** Locks the seats currently owned by an order in a section (HELD or SOLD). */
    public List<Seat> lockOrderSeats(String concertId, String sectionId, String orderId) {
        return query("select " + COLS + " from seat where concert_id = ? and section_id = ? "
                + "and order_id = CAST(? AS uuid) order by seat_id for update", (c, ps) -> {
            ps.setString(1, concertId);
            ps.setString(2, sectionId);
            ps.setString(3, orderId);
        });
    }

    /** Expired HELD seats, skipping rows another replica (or a hold command) holds locked. */
    public List<Seat> lockExpired(int limit) {
        return query("select " + COLS + " from seat where state = 'HELD' and held_until < clock_timestamp() "
                + "order by held_until, seat_id limit ? for update skip locked", (c, ps) -> ps.setInt(1, limit));
    }

    /** Locks every seat (deterministic order), used by the reset. */
    public List<Seat> lockAll() {
        return query("select " + COLS + " from seat order by concert_id, seat_id for update", (c, ps) -> {});
    }

    /**
     * Conditional transition (layer 3 of the anti-oversell guarantee): the row must still be in the state,
     * version and ownership we read under lock, otherwise the update affects 0 rows and we abort.
     *
     * @return the new version
     */
    public long transition(Seat s, String toState, String toOrderId, String toBuyerId, Long toHeldUntilMs) {
        String sql = "update seat set state = ?, order_id = CAST(? AS uuid), buyer_id = ?, held_until = ?, "
                + "version = version + 1, updated_at = clock_timestamp() "
                + "where concert_id = ? and seat_id = ? and state = ? and version = ? "
                + "and order_id IS NOT DISTINCT FROM CAST(? AS uuid)";
        int n = update(sql, ps -> {
            ps.setString(1, toState);
            setNullableString(ps, 2, toOrderId);
            setNullableString(ps, 3, toBuyerId);
            if (toHeldUntilMs == null) {
                ps.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
            } else {
                ps.setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(toHeldUntilMs)));
            }
            ps.setString(5, s.concertId());
            ps.setString(6, s.seatId());
            ps.setString(7, s.state());
            ps.setLong(8, s.version());
            setNullableString(ps, 9, s.orderId());
        });
        if (n != 1) {
            throw new IllegalStateException("Conditional update lost the race for seat " + s.seatId()
                    + " (expected " + s.state() + " v" + s.version() + ")");
        }
        return s.version() + 1;
    }

    /** Moves a HELD seat's deadline without a state transition (version unchanged). */
    public void setHeldUntil(Seat s, long heldUntilMs) {
        int n = update("update seat set held_until = ?, updated_at = clock_timestamp() "
                + "where concert_id = ? and seat_id = ? and state = 'HELD' and version = ? "
                + "and order_id = CAST(? AS uuid)", ps -> {
            ps.setTimestamp(1, Timestamp.from(Instant.ofEpochMilli(heldUntilMs)));
            ps.setString(2, s.concertId());
            ps.setString(3, s.seatId());
            ps.setLong(4, s.version());
            ps.setString(5, s.orderId());
        });
        if (n != 1) {
            throw new IllegalStateException("Conditional extend lost the race for seat " + s.seatId());
        }
    }

    public long count() {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("select count(*) from seat");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    interface Binder {
        void bind(Connection c, PreparedStatement ps) throws SQLException;
    }

    interface PsBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private List<Seat> query(String sql, Binder binder) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(c, ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<Seat> out = new ArrayList<>();
                while (rs.next()) {
                    long hu = rs.getLong(7);
                    Long held = rs.wasNull() ? null : hu;
                    out.add(new Seat(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), held, rs.getLong(8), rs.getLong(9)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int update(String sql, PsBinder binder) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setNullableString(PreparedStatement ps, int i, String v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.VARCHAR);
        } else {
            ps.setString(i, v);
        }
    }
}
