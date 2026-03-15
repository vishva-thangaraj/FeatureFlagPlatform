package com.feature_flag.platform.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TokenHashUtilTest {

    @Test
    void sha256_returnsConsistentHash() {
        String token = "my-refresh-token";
        String hash1 = TokenHashUtil.sha256(token);
        String hash2 = TokenHashUtil.sha256(token);

        assertNotNull(hash1);
        assertEquals(hash1, hash2);
    }

    @Test
    void sha256_returnsHexString64Chars() {
        String hash = TokenHashUtil.sha256("some-token");

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"));
    }

    @Test
    void sha256_differentInputsProduceDifferentHashes() {
        String hash1 = TokenHashUtil.sha256("token-a");
        String hash2 = TokenHashUtil.sha256("token-b");

        assertNotEquals(hash1, hash2);
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }
}
