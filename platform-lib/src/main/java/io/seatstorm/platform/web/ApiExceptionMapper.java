package io.seatstorm.platform.web;

import io.quarkus.logging.Log;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/** Maps every exception to {@code {code, message, details}} (spec 6.4). */
@Provider
public class ApiExceptionMapper implements ExceptionMapper<Throwable> {

    @Override
    public Response toResponse(Throwable t) {
        if (t instanceof ApiException e) {
            return build(e.code().status(), e.code().name(), e.getMessage(), e.details());
        }
        if (t instanceof WebApplicationException w) {
            int status = w.getResponse().getStatus();
            if (status >= 400 && status < 500) {
                ErrorCode code = switch (status) {
                    case 404 -> ErrorCode.NOT_FOUND;
                    case 401 -> ErrorCode.NO_SESSION;
                    case 403 -> ErrorCode.NOT_YOUR_ORDER;
                    case 429 -> ErrorCode.RATE_LIMITED;
                    default -> ErrorCode.VALIDATION_ERROR;
                };
                return build(status, code.name(), messageFor(code), Map.of());
            }
        }
        if (t instanceof IllegalArgumentException) {
            return build(400, ErrorCode.VALIDATION_ERROR.name(), "Solicitud inválida", Map.of("reason", String.valueOf(t.getMessage())));
        }
        Log.error("Unhandled exception", t);
        return build(500, ErrorCode.INTERNAL_ERROR.name(), "Error interno", Map.of());
    }

    static String messageFor(ErrorCode code) {
        return switch (code) {
            case NOT_FOUND -> "No encontrado";
            case NO_SESSION -> "Sin sesión";
            case NOT_YOUR_ORDER -> "No permitido";
            case RATE_LIMITED -> "Demasiadas solicitudes";
            default -> "Solicitud inválida";
        };
    }

    static Response build(int status, String code, String message, Map<String, Object> details) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(new ErrorBody(code, message, details)).build();
    }
}
