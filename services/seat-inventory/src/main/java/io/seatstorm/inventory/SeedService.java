package io.seatstorm.inventory;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.seatstorm.contracts.Sections;
import io.seatstorm.contracts.VenueLayout;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;

/** Idempotent seed: inserts the 360 seats when the table is empty (ON CONFLICT keeps concurrent starts safe). */
@ApplicationScoped
public class SeedService {

    @Inject
    DataSource ds;

    @Inject
    SeatDao dao;

    void onStart(@Observes StartupEvent ev) {
        seed();
    }

    @Transactional
    public int seed() {
        if (dao.count() > 0) {
            Log.info("Seats already seeded");
            return 0;
        }
        String sql = "insert into seat (concert_id, seat_id, section_id, row_label, seat_number, price_cents) "
                + "values (?, ?, ?, ?, ?, ?) on conflict do nothing";
        int n = 0;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (Sections.Section s : Sections.ALL) {
                for (String seatId : VenueLayout.seatIdsOf(s.id())) {
                    ps.setString(1, VenueLayout.CONCERT_ID);
                    ps.setString(2, seatId);
                    ps.setString(3, s.id());
                    ps.setString(4, VenueLayout.rowOf(seatId));
                    ps.setInt(5, VenueLayout.numberOf(seatId));
                    ps.setInt(6, s.priceCents());
                    ps.addBatch();
                    n++;
                }
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        Log.infof("Seeded %d seats", n);
        return n;
    }
}
