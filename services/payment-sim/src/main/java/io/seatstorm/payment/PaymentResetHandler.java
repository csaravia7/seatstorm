package io.seatstorm.payment;

import io.seatstorm.platform.control.ResetHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/** Reset: empties payment and restores the default configuration (mode OK); the pauser then resumes the channel. */
@ApplicationScoped
public class PaymentResetHandler implements ResetHandler {
    @Inject
    EntityManager em;

    @Override
    public void reset(String resetId) {
        em.createNativeQuery("truncate table payment").executeUpdate();
        em.createNativeQuery("update payment_config set mode = 'OK', failure_rate = 0.15, latency_ms = 1500, "
                + "updated_at = now() where id = 1").executeUpdate();
    }
}
