package com.feature_flag.platform.common.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenHashUtilTest {

    @Test
    void sha256_returnsConsistentHash() {
        String hash1 = TokenHashUtil.sha256("my-refresh-token");
        String hash2 = TokenHashUtil.sha256("my-refresh-token");
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
        assertNotEquals(TokenHashUtil.sha256("token-a"), TokenHashUtil.sha256("token-b"));
    }
}
