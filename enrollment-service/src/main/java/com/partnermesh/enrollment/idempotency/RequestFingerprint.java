package com.partnermesh.enrollment.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Stable SHA-256 fingerprint of the semantically relevant request fields.
 *
 * <p>Fields are length-prefixed so that ("ab", "c") and ("a", "bc") never collide, and normalised
 * (trimmed; e-mail lower-cased) so that a client retrying the same logical request with cosmetic
 * differences is recognised as a replay rather than as reuse of the key for a different request.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String of(String partnerId, String programCode, String contactEmail) {
        StringBuilder canonical = new StringBuilder();
        for (String field : new String[] {
                partnerId.trim(), programCode.trim(), contactEmail.trim().toLowerCase(Locale.ROOT)}) {
            canonical.append(field.length()).append(':').append(field).append('|');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JVM", impossible);
        }
    }
}
