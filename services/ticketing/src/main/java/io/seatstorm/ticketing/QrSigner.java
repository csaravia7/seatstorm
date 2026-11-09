package io.seatstorm.ticketing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * QR payload {@code v1.<ticketId>.<orderId>.<hmac>} where hmac = base64url(HMAC_SHA256(secret, ticketId + "." + orderId)),
 * without padding. UUIDs contain no dots, so the payload splits unambiguously on ".".
 */
public final class QrSigner {
    public static final String VERSION = "v1";

    private QrSigner() {}

    public static String sign(String secret, String ticketId, String orderId) {
        return VERSION + "." + ticketId + "." + orderId + "." + hmac(secret, ticketId, orderId);
    }

    /** True when the payload is well formed and the signature matches (constant-time comparison). */
    public static boolean verify(String secret, String payload) {
        if (payload == null) {
            return false;
        }
        String[] p = payload.split("\\.", -1);
        if (p.length != 4 || !VERSION.equals(p[0]) || p[1].isEmpty() || p[2].isEmpty() || p[3].isEmpty()) {
            return false;
        }
        byte[] expected = hmac(secret, p[1], p[2]).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, p[3].getBytes(StandardCharsets.UTF_8));
    }

    private static String hmac(String secret, String ticketId, String orderId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((ticketId + "." + orderId).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
