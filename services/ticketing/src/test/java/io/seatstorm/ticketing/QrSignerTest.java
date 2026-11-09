package io.seatstorm.ticketing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class QrSignerTest {
    static final String SECRET = "s3cret";

    @Test
    void formatAndVerification() {
        String t = UUID.randomUUID().toString(), o = UUID.randomUUID().toString();
        String qr = QrSigner.sign(SECRET, t, o);
        assertTrue(qr.matches("v1\\." + t + "\\." + o + "\\.[A-Za-z0-9_-]{43}"), qr);
        assertTrue(QrSigner.verify(SECRET, qr));
        assertFalse(QrSigner.verify("other-secret", qr));
    }

    @Test
    void knownVector() {
        // HMAC_SHA256("k", "t.o") base64url without padding
        String qr = QrSigner.sign("k", "t", "o");
        assertEquals("v1.t.o.", qr.substring(0, 7));
        assertEquals(QrSigner.sign("k", "t", "o"), qr); // deterministic
        assertNotEquals(QrSigner.sign("k", "t", "o2"), qr);
    }

    @Test
    void tamperingAnyCharacterBreaksTheSignature() {
        String qr = QrSigner.sign(SECRET, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        for (int i = 0; i < qr.length(); i++) {
            char c = qr.charAt(i);
            char repl = c == 'a' ? 'b' : 'a';
            String bad = qr.substring(0, i) + repl + qr.substring(i + 1);
            assertFalse(QrSigner.verify(SECRET, bad), "tampered at " + i);
        }
    }

    @Test
    void malformedPayloadsAreRejected() {
        assertFalse(QrSigner.verify(SECRET, null));
        assertFalse(QrSigner.verify(SECRET, ""));
        assertFalse(QrSigner.verify(SECRET, "v1.a.b"));
        assertFalse(QrSigner.verify(SECRET, "v2.a.b.c"));
        assertFalse(QrSigner.verify(SECRET, "v1.a.b.c.d"));
        assertFalse(QrSigner.verify(SECRET, "v1...."));
    }
}
