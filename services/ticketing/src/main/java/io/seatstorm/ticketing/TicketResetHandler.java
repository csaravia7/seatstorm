package io.seatstorm.ticketing;

import io.seatstorm.platform.control.ResetHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

@ApplicationScoped
public class TicketResetHandler implements ResetHandler {
    @Inject
    EntityManager em;

    @Override
    public void reset(String resetId) {
        em.createNativeQuery("truncate table ticket").executeUpdate();
    }
}
