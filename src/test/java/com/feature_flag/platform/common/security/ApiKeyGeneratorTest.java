package com.feature_flag.platform.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** What a generated key must guarantee before anything is allowed to authenticate with one. */
class ApiKeyGeneratorTest {

    @Test
    @DisplayName("the stored hash is not the key, and the stored prefix is not enough to reconstruct it")
    void neverStoresTheSecret() {
        ApiKeyGenerator.GeneratedKey key = ApiKeyGenerator.generate();

        assertThat(key.keyHash()).isNotEqualTo(key.plaintext());
        assertThat(key.keyHash()).isEqualTo(TokenHashUtil.sha256(key.plaintext()));
        assertThat(key.plaintext()).startsWith(key.prefix());
        assertThat(key.prefix().length()).isLessThan(key.plaintext().length() / 2);
    }

    @Test
    @DisplayName("keys do not repeat")
    void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            seen.add(ApiKeyGenerator.generate().plaintext());
        }
        assertThat(seen).hasSize(1_000);
    }

    @Test
    @DisplayName("the shape check accepts real keys and rejects everything else")
    void shapeCheck() {
        String real = ApiKeyGenerator.generate().plaintext();

        assertThat(ApiKeyGenerator.hasKeyShape(real)).isTrue();
        assertThat(ApiKeyGenerator.hasKeyShape(null)).isFalse();
        assertThat(ApiKeyGenerator.hasKeyShape("")).isFalse();
        assertThat(ApiKeyGenerator.hasKeyShape(real.substring(1))).isFalse();
        assertThat(ApiKeyGenerator.hasKeyShape(real + "x")).isFalse();
        // A JWT is the credential most likely to be sent to the wrong header.
        assertThat(ApiKeyGenerator.hasKeyShape("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.xxxxx")).isFalse();
    }
}
