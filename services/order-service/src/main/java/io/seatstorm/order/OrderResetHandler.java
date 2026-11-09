package io.seatstorm.order;

import io.seatstorm.platform.control.ResetHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/** Reset: empties orders, keeps sale_state (the SaleClosed from event-catalog will close it). */
@ApplicationScoped
public class OrderResetHandler implements ResetHandler {
    @Inject
    EntityManager em;

    @Override
    public void reset(String resetId) {
        em.createNativeQuery("truncate table orders").executeUpdate();
    }
}
