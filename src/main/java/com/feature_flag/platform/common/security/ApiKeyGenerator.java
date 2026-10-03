package com.feature_flag.platform.common.security;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Mints SDK keys: {@code ffsdk_} followed by 32 random bytes, base64url without padding.
 *
 * <p>Stored as a SHA-256 digest rather than a bcrypt hash. Bcrypt exists to slow a dictionary attack on
 * low-entropy input and there is no dictionary for 256 random bits; it also cannot be looked up by hash,
 * so verifying a key would mean scanning the table at ~100ms a row on an endpoint SDKs poll.
 */
public final class ApiKeyGenerator {

    public static final String PREFIX = "ffsdk_";

    private static final int SECRET_BYTES = 32;
    private static final int SECRET_CHARS = 43;
    private static final int DISPLAY_PREFIX_LENGTH = PREFIX.length() + 6;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private ApiKeyGenerator() {
    }

    /** The plaintext is the caller's only copy; {@code keyHash} and {@code prefix} are what gets stored. */
    public record GeneratedKey(String plaintext, String keyHash, String prefix) {
    }

    public static GeneratedKey generate() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        String plaintext = PREFIX + ENCODER.encodeToString(secret);
        return new GeneratedKey(plaintext, TokenHashUtil.sha256(plaintext),
                plaintext.substring(0, DISPLAY_PREFIX_LENGTH));
    }

    /** Cheap shape check, so a value that cannot be one of our keys costs no database round trip. */
    public static boolean hasKeyShape(String candidate) {
        return candidate != null
                && candidate.length() == PREFIX.length() + SECRET_CHARS
                && candidate.startsWith(PREFIX);
    }
}
