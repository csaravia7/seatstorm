package io.seatstorm.payment;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment")
public class PaymentEntity extends PanacheEntityBase {
    public static final String AUTHORIZED = "AUTHORIZED";
    public static final String FAILED = "FAILED";
    public static final String REFUNDED = "REFUNDED";

    @Id
    @Column(name = "payment_id")
    public UUID paymentId;

    @Column(name = "order_id", nullable = false, unique = true)
    public UUID orderId;

    @Column(name = "amount_cents", nullable = false)
    public int amountCents;

    @Column(nullable = false)
    public String status;

    public String reason;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    public static PaymentEntity byOrder(UUID orderId) {
        return find("orderId", orderId).firstResult();
    }
}
