package io.seatstorm.platform.inbox;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.util.UUID;

@ApplicationScoped
public class InboxService implements Inbox {

    @Inject
    EntityManager em;

    @Override
    public boolean firstTime(String consumer, String eventId) {
        UUID id = UUID.fromString(eventId);
        // Requires an active transaction (TransactionRequiredException otherwise).
        int n = em.createNativeQuery(
                        "insert into inbox (consumer, event_id) values (?1, ?2) on conflict do nothing")
                .setParameter(1, consumer).setParameter(2, id).executeUpdate();
        return n == 1;
    }
}
