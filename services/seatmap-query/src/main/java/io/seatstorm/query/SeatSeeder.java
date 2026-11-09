package io.seatstorm.query;

import io.agroal.api.AgroalDataSource;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.seatstorm.contracts.VenueLayout;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Seeds the 360 seats (AVAILABLE, version 0) when seat_view is empty. */
@ApplicationScoped
public class SeatSeeder {

    @Inject
    AgroalDataSource ds;

    void onStart(@Observes StartupEvent ev) {
        seedIfEmpty();
    }

    @Transactional
    public void seedIfEmpty() {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement q = c.prepareStatement("select count(*) from seat_view where concert_id = ?")) {
                q.setString(1, VenueLayout.CONCERT_ID);
                try (ResultSet rs = q.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        return;
                    }
                }
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "insert into seat_view (concert_id, seat_id, section_id, row_label, seat_number, state, version) "
                            + "values (?, ?, ?, ?, ?, 'AVAILABLE', 0) on conflict do nothing")) {
                for (String seatId : VenueLayout.allSeatIds()) {
                    ins.setString(1, VenueLayout.CONCERT_ID);
                    ins.setString(2, seatId);
                    ins.setString(3, VenueLayout.sectionOf(seatId));
                    ins.setString(4, VenueLayout.rowOf(seatId));
                    ins.setInt(5, VenueLayout.numberOf(seatId));
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            Log.infof("Seeded %d seats in seat_view", VenueLayout.TOTAL_SEATS);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot seed seat_view", e);
        }
    }
}
