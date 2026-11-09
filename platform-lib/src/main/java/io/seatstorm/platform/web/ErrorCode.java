package io.seatstorm.platform.web;

/** Error codes of the REST error model (spec 6.4) with their HTTP status. */
public enum ErrorCode {
    VALIDATION_ERROR(400),
    NO_SESSION(401),
    BAD_ADMIN_TOKEN(401),
    NOT_YOUR_ORDER(403),
    NOT_FOUND(404),
    SALE_CLOSED(409),
    ACTIVE_ORDER_EXISTS(409),
    INVALID_STATE(409),
    HOLD_EXPIRED(409),
    SEAT_TAKEN(409),
    RATE_LIMITED(429),
    UPSTREAM_UNAVAILABLE(503),
    INTERNAL_ERROR(500);

    private final int status;

    ErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
