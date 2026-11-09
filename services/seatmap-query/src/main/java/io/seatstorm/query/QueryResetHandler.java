package io.seatstorm.query;

import io.seatstorm.platform.control.ResetHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Reset: empties order_view and order_timeline; seat_view is refreshed by the SeatsReleased(RESET) events. */
@ApplicationScoped
public class QueryResetHandler implements ResetHandler {

    @Inject
    Projector projector;

    @Override
    public void reset(String resetId) {
        projector.resetOrders();
    }
}
