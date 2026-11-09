package io.seatstorm.order;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "orders")
public class OrderEntity extends PanacheEntityBase {
    public static final String PLACED = "PLACED";
    public static final String HELD = "HELD";
    public static final String PAYING = "PAYING";
    public static final String CONFIRMING = "CONFIRMING";
    public static final String REFUNDING = "REFUNDING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";

    @Id
    @Column(name = "order_id")
    public UUID orderId;

    @Column(name = "buyer_id", nullable = false)
    public String buyerId;

    @Column(nullable = false)
    public String nickname;

    @Column(name = "concert_id", nullable = false)
    public String concertId;

    @Column(name = "section_id", nullable = false)
    public String sectionId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "seat_ids", nullable = false, columnDefinition = "text[]")
    public List<String> seatIds;

    @Column(name = "total_cents", nullable = false)
    public int totalCents;

    @Column(nullable = false, length = 3)
    public String currency;

    @Column(nullable = false)
    public String status;

    public String reason;

    @Column(name = "held_until")
    public Instant heldUntil;

    @Column(name = "payment_id")
    public String paymentId;

    @Column(name = "idempotency_key", nullable = false)
    public String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    public Long heldUntilMs() {
        return heldUntil == null ? null : heldUntil.toEpochMilli();
    }
}
