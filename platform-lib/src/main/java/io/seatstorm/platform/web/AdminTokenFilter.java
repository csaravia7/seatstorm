package io.seatstorm.platform.web;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Requires {@code X-Admin-Token} (config {@code seatstorm.admin-token}, env ADMIN_TOKEN) on every /admin/* request. */
@Provider
@PreMatching
public class AdminTokenFilter implements ContainerRequestFilter {

    public static final String HEADER = "X-Admin-Token";

    @ConfigProperty(name = "seatstorm.admin-token")
    String expected;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (!(path.equals("admin") || path.startsWith("admin/"))) {
            return;
        }
        String given = ctx.getHeaderString(HEADER);
        boolean ok = given != null && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            ctx.abortWith(ApiExceptionMapper.build(401, ErrorCode.BAD_ADMIN_TOKEN.name(),
                    "Token de administración inválido", java.util.Map.of()));
        }
    }
}
