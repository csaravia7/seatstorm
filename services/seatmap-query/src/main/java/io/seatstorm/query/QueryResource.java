package io.seatstorm.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.seatstorm.contracts.Sections;
import io.seatstorm.contracts.VenueLayout;
import io.seatstorm.platform.web.ApiException;
import io.seatstorm.platform.web.ErrorCode;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only REST API of the CQRS read model (spec 6.4). */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class QueryResource {

    static final int MAX_GROUPS = 5;

    @Inject
    AgroalDataSource ds;

    @Inject
    ObjectMapper json;

    @GET
    @Path("/seatmap")
    public Map<String, Object> seatmap(@QueryParam("concertId") String concertId) {
        String concert = concert(concertId);
        Map<String, List<Map<String, Object>>> bySection = new LinkedHashMap<>();
        Sections.ALL.forEach(s -> bySection.put(s.id(), new ArrayList<>()));
        long[] count = {0};
        query("select section_id, seat_id, row_label, seat_number, state, version from seat_view "
                + "where concert_id = ? order by section_id, row_label, seat_number", ps -> ps.setString(1, concert), rs -> {
            Map<String, Object> seat = new LinkedHashMap<>();
            seat.put("seatId", rs.getString("seat_id"));
            seat.put("row", rs.getString("row_label"));
            seat.put("number", rs.getInt("seat_number"));
            seat.put("state", rs.getString("state"));
            seat.put("version", rs.getLong("version"));
            bySection.computeIfAbsent(rs.getString("section_id"), k -> new ArrayList<>()).add(seat);
            count[0]++;
        });
        if (count[0] == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Concierto no encontrado: " + concert);
        }
        List<Map<String, Object>> sections = new ArrayList<>();
        for (Sections.Section s : Sections.ALL) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sectionId", s.id());
            m.put("index", s.index());
            m.put("label", s.label());
            m.put("priceCents", s.priceCents());
            m.put("seats", bySection.get(s.id()));
            sections.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("concertId", concert);
        out.put("asOfMs", System.currentTimeMillis());
        out.put("sections", sections);
        return out;
    }

    @GET
    @Path("/stats")
    public Map<String, Object> stats(@QueryParam("concertId") String concertId) {
        String concert = concert(concertId);
        Map<String, Object> out = new LinkedHashMap<>();
        long[] c = new long[3];
        query("select state, count(*) n from seat_view where concert_id = ? group by state", ps -> ps.setString(1, concert), rs -> {
            switch (rs.getString("state")) {
                case "AVAILABLE" -> c[0] = rs.getLong("n");
                case "HELD" -> c[1] = rs.getLong("n");
                case "SOLD" -> c[2] = rs.getLong("n");
                default -> { }
            }
        });
        String[] sale = {"CLOSED"};
        query("select status from sale_view where concert_id = ?", ps -> ps.setString(1, concert), rs -> sale[0] = rs.getString(1));
        out.put("available", c[0]);
        out.put("held", c[1]);
        out.put("sold", c[2]);
        out.put("saleStatus", sale[0]);
        return out;
    }

    @GET
    @Path("/orders/{orderId}/timeline")
    public List<Map<String, Object>> timeline(@PathParam("orderId") String orderId) {
        UUID id = uuid(orderId);
        List<Map<String, Object>> out = new ArrayList<>();
        query("select event_type, occurred_at, cast(detail as text) detail from order_timeline "
                + "where order_id = ? order by occurred_at, id", ps -> ps.setObject(1, id), rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventType", rs.getString("event_type"));
            m.put("occurredAtMs", rs.getTimestamp("occurred_at").getTime());
            m.put("detail", parse(rs.getString("detail")));
            out.add(m);
        });
        return out;
    }

    @GET
    @Path("/buyers/{buyerId}/orders")
    public List<Map<String, Object>> buyerOrders(@PathParam("buyerId") String buyerId,
            @QueryParam("limit") @DefaultValue("5") int limit) {
        if (limit < 1 || limit > 50) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "limit debe estar entre 1 y 50");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        query("select order_id, concert_id, section_id, seat_ids, total_cents, status, reason, held_until, placed_at, "
                + "confirmed_at, ticket_id, ticket_qr from order_view where buyer_id = ? "
                + "order by placed_at desc, updated_at desc limit ?", ps -> {
            ps.setString(1, buyerId);
            ps.setInt(2, limit);
        }, rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId", rs.getString("order_id"));
            m.put("concertId", rs.getString("concert_id"));
            m.put("status", rs.getString("status"));
            m.put("reason", rs.getString("reason"));
            m.put("sectionId", rs.getString("section_id"));
            m.put("seatIds", List.of((String[]) rs.getArray("seat_ids").getArray()));
            m.put("totalCents", rs.getInt("total_cents"));
            m.put("heldUntilMs", millis(rs.getTimestamp("held_until")));
            m.put("placedAtMs", millis(rs.getTimestamp("placed_at")));
            m.put("confirmedAtMs", millis(rs.getTimestamp("confirmed_at")));
            m.put("ticketId", rs.getString("ticket_id"));
            m.put("ticketQr", rs.getString("ticket_qr"));
            out.add(m);
        });
        return out;
    }

    @GET
    @Path("/seats/search")
    public List<Map<String, Object>> search(@QueryParam("concertId") String concertId,
            @QueryParam("sectionId") String sectionId,
            @QueryParam("quantity") @DefaultValue("1") int quantity,
            @QueryParam("adjacent") @DefaultValue("false") boolean adjacent) {
        String concert = concert(concertId);
        if (quantity < 1 || quantity > 4) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "quantity debe estar entre 1 y 4");
        }
        if (sectionId != null && !sectionId.isBlank() && !Sections.exists(sectionId)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Sección desconocida: " + sectionId);
        }
        boolean filtered = sectionId != null && !sectionId.isBlank();
        // seat id, section, row, number of AVAILABLE seats in order
        List<String[]> free = new ArrayList<>();
        query("select seat_id, section_id, row_label, seat_number from seat_view where concert_id = ? and state = 'AVAILABLE' "
                + (filtered ? "and section_id = ? " : "") + "order by section_id, row_label, seat_number", ps -> {
            ps.setString(1, concert);
            if (filtered) {
                ps.setString(2, sectionId);
            }
        }, rs -> free.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3), String.valueOf(rs.getInt(4))}));

        Map<String, List<List<String>>> perSection = new LinkedHashMap<>();
        for (Sections.Section s : Sections.ALL) {
            List<String[]> seats = free.stream().filter(f -> f[1].equals(s.id())).toList();
            if (!seats.isEmpty()) {
                perSection.put(s.id(), groups(seats, quantity, adjacent));
            }
        }
        // Round-robin across sections so the answer offers variety
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; out.size() < MAX_GROUPS; i++) {
            boolean any = false;
            for (Map.Entry<String, List<List<String>>> e : perSection.entrySet()) {
                if (i < e.getValue().size() && out.size() < MAX_GROUPS) {
                    any = true;
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("sectionId", e.getKey());
                    g.put("seatIds", e.getValue().get(i));
                    g.put("priceCents", Sections.priceCents(e.getKey()));
                    out.add(g);
                }
            }
            if (!any) {
                break;
            }
        }
        return out;
    }

    /** Non-overlapping groups of {@code quantity} seats of one section; adjacent = consecutive numbers in one row. */
    static List<List<String>> groups(List<String[]> seats, int quantity, boolean adjacent) {
        List<List<String>> out = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        String lastRow = null;
        int lastNumber = -10;
        for (String[] s : seats) {
            int number = Integer.parseInt(s[3]);
            if (adjacent && (!s[2].equals(lastRow) || number != lastNumber + 1)) {
                cur.clear();
            }
            cur.add(s[0]);
            lastRow = s[2];
            lastNumber = number;
            if (cur.size() == quantity) {
                out.add(List.copyOf(cur));
                cur.clear();
            }
        }
        return out;
    }

    @GET
    @Path("/leaderboard")
    public List<Map<String, Object>> leaderboard(@QueryParam("concertId") String concertId,
            @QueryParam("limit") @DefaultValue("10") int limit) {
        String concert = concert(concertId);
        if (limit < 1 || limit > 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "limit debe estar entre 1 y 100");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        query("select max(nickname) nickname, sum(cardinality(seat_ids)) tickets, "
                + "min(extract(epoch from (confirmed_at - placed_at)) * 1000)::bigint fastest "
                + "from order_view where concert_id = ? and status = 'CONFIRMED' and confirmed_at is not null "
                + "group by buyer_id order by tickets desc, fastest asc, nickname limit ?", ps -> {
            ps.setString(1, concert);
            ps.setInt(2, limit);
        }, rs -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nickname", rs.getString("nickname"));
            m.put("tickets", rs.getLong("tickets"));
            m.put("fastestMs", rs.getLong("fastest"));
            out.add(m);
        });
        return out;
    }

    // ---- helpers

    private static String concert(String concertId) {
        return concertId == null || concertId.isBlank() ? VenueLayout.CONCERT_ID : concertId;
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Identificador de orden inválido");
        }
    }

    private static Long millis(Timestamp t) {
        return t == null ? null : t.getTime();
    }

    private Object parse(String s) {
        try {
            return json.readValue(s, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    @FunctionalInterface
    interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    interface RowReader {
        void read(ResultSet rs) throws SQLException;
    }

    private void query(String sql, Binder binder, RowReader reader) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    reader.read(rs);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
