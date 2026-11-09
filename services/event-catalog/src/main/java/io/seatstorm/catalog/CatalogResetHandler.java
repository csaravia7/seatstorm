package io.seatstorm.catalog;

import io.seatstorm.contracts.VenueLayout;
import io.seatstorm.platform.control.ResetHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/** The catalog's own reset: sale goes back to CLOSED and SaleClosed is always published (other services sync). */
@ApplicationScoped
public class CatalogResetHandler implements ResetHandler {

    @Inject
    EntityManager em;

    @Inject
    CatalogService catalog;

    @Override
    public void reset(String resetId) {
        em.createNativeQuery("update concert set sale_status = 'CLOSED' where concert_id = ?1")
                .setParameter(1, VenueLayout.CONCERT_ID).executeUpdate();
        catalog.publishSale(VenueLayout.CONCERT_ID, false);
    }
}
