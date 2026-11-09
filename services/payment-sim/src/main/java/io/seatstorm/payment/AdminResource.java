package io.seatstorm.payment;

import io.seatstorm.platform.web.ApiException;
import io.seatstorm.platform.web.ErrorCode;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** /admin/* is protected by the platform AdminTokenFilter (X-Admin-Token). */
@Path("/admin/mode")
@Produces(MediaType.APPLICATION_JSON)
public class AdminResource {
    static final Set<String> MODES = Set.of("OK", "FLAKY", "SLOW", "PAUSED");
    static final int MAX_LATENCY_MS = 60_000;

    public record ModeRequest(String mode, Double failureRate, Integer latencyMs) {}

    public record ModeView(String mode, double failureRate, int latencyMs, long updatedAtMs) {}

    @GET
    @Transactional
    public ModeView get() {
        return view(PaymentConfig.get());
    }

    @PUT
    @Transactional
    public ModeView put(ModeRequest req) {
        if (req == null || req.mode() == null || !MODES.contains(req.mode())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Modo inválido (OK, FLAKY, SLOW, PAUSED)",
                    Map.of("allowed", MODES.stream().sorted().toList()));
        }
        if (req.failureRate() != null && (req.failureRate().isNaN() || req.failureRate() < 0 || req.failureRate() > 1)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "failureRate debe estar entre 0 y 1");
        }
        if (req.latencyMs() != null && (req.latencyMs() < 0 || req.latencyMs() > MAX_LATENCY_MS)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "latencyMs debe estar entre 0 y " + MAX_LATENCY_MS);
        }
        PaymentConfig c = PaymentConfig.get();
        c.mode = req.mode();
        if (req.failureRate() != null) {
            c.failureRate = BigDecimal.valueOf(req.failureRate()).setScale(2, RoundingMode.HALF_UP);
        }
        if (req.latencyMs() != null) {
            c.latencyMs = req.latencyMs();
        }
        c.updatedAt = Instant.now();
        return view(c);
    }

    static ModeView view(PaymentConfig c) {
        return new ModeView(c.mode, c.failureRate.doubleValue(), c.latencyMs, c.updatedAt.toEpochMilli());
    }
}
