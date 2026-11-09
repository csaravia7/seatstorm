package io.seatstorm.inventory;

import io.seatstorm.platform.control.ResetHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Reset (runs inside the DemoControlConsumer transaction): all seats AVAILABLE, SeatsReleased(RESET) per section. */
@ApplicationScoped
public class InventoryResetHandler implements ResetHandler {

    @Inject
    SeatService seats;

    @Override
    public void reset(String resetId) {
        seats.resetAll(resetId);
    }
}
