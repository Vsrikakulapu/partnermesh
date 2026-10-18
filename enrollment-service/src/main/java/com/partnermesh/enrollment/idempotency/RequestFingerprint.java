package com.partnermesh.enrollment.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Stable SHA-256 fingerprint of the semantically relevant request fields. */
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
