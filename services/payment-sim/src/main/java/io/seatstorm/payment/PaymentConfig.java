package io.seatstorm.payment;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_config")
public class PaymentConfig extends PanacheEntityBase {
    @Id
    public Integer id;

    @Column(nullable = false)
    public String mode;

    @Column(name = "failure_rate", nullable = false)
    public BigDecimal failureRate;

    @Column(name = "latency_ms", nullable = false)
    public int latencyMs;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    public static PaymentConfig get() {
        return findById(1);
    }
}
