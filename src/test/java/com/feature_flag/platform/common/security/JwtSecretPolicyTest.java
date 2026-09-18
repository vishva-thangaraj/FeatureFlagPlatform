package com.feature_flag.platform.common.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The startup guard on the HS256 signing secret.
 *
 * <p>The key below was committed to this repository twice — as the {@code JWT_SECRET} default in
 * {@code docker-compose.yml} and as the {@code jwt.secret} default of the {@code dev} profile, which is
 * also the profile the app falls back to when {@code SPRING_PROFILES_ACTIVE} is unset. Anyone who could
 * read the repository could forge an HS256 token with any {@code sub}, {@code email}, {@code tenantId} and
 * {@code roles:["OWNER"]} against any deployment that had not overridden it.
 */
class JwtSecretPolicyTest {

    /** The key that was committed to this repository. Public knowledge, forever. */
    private static final String PUBLISHED = "dGhpcy1pcy1hLWRldi1vbmx5LWp3dC1zZWNyZXQta2V5LTMyYg==";

    private static final String GOOD = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    // ---------------------------------------------------------------- the published key is dead

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging", "docker", "dev", "test", "local", "default"})
    void thePublishedKeyIsRefusedInEveryProfile(String profile) {
        assertThatThrownBy(() -> JwtSecretPolicy.resolve(PUBLISHED, List.of(profile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publicly published");
    }

    /** No amount of whitespace in the environment variable smuggles it back in. */
    @Test
    void thePublishedKeyIsRefusedEvenWhenPadded() {
        assertThatThrownBy(() -> JwtSecretPolicy.resolve("  " + PUBLISHED + "\n", List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("publicly published");
    }

    // ---------------------------------------------------------------- weak and missing secrets

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging", "docker", "dev", "test"})
    void aTooShortSecretIsRefusedInEveryProfile(String profile) {
        String sixteenBytes = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes());

        assertThatThrownBy(() -> JwtSecretPolicy.resolve(sixteenBytes, List.of(profile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16 bytes");
    }

    @Test
    void aSecretThatIsNotBase64IsRefused() {
        assertThatThrownBy(() -> JwtSecretPolicy.resolve("not base64 at all !!!", List.of("prod")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid base64");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging", "docker", "default"})
    void aMissingSecretRefusesToStartOutsideDev(String profile) {
        assertThatThrownBy(() -> JwtSecretPolicy.resolve(null, List.of(profile)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no JWT secret is configured");
        assertThatThrownBy(() -> JwtSecretPolicy.resolve("", List.of(profile)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Fail closed: an unknown or absent profile is production as far as this guard is concerned. */
    @Test
    void anEmptyProfileSetCountsAsProduction() {
        assertThatThrownBy(() -> JwtSecretPolicy.resolve(null, List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 'dev' alongside anything unrecognised is not dev — the strictest member of the set wins. */
    @Test
    void devMixedWithAnUnknownProfileCountsAsProduction() {
        assertThatThrownBy(() -> JwtSecretPolicy.resolve(null, List.of("dev", "prod")))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------- dev keeps working

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "local"})
    void aMissingSecretIsGeneratedPerBootInDev(String profile) {
        String first = JwtSecretPolicy.resolve(null, List.of(profile));
        String second = JwtSecretPolicy.resolve(null, List.of(profile));

        assertThat(Base64.getDecoder().decode(first))
                .as("a generated key must be a full-strength one")
                .hasSize(JwtSecretPolicy.MIN_SECRET_BYTES);
        assertThat(first)
                .as("generated per boot, so nothing forgeable is ever written down")
                .isNotEqualTo(second);
    }

    @Test
    void aStrongConfiguredSecretIsAcceptedEverywhere() {
        assertThatCode(() -> JwtSecretPolicy.resolve(GOOD, List.of("prod"))).doesNotThrowAnyException();
        assertThat(JwtSecretPolicy.resolve(GOOD, List.of("prod"))).isEqualTo(GOOD);
    }

    // ---------------------------------------------------------------- nothing committed ships a key

    /**
     * The guard above only helps if nobody puts the key back. These are the exact files the leak lived in.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "docker-compose.yml",
            "docker-compose.dev.yml",
            "src/main/resources/application.properties",
            "src/main/resources/application-dev.properties",
            "src/main/resources/application-docker.properties",
            "src/main/resources/application-prod.properties",
            "src/main/resources/application-staging.properties",
            ".env.example",
            ".env.prod.example"
    })
    void noCheckedInConfigurationCarriesThePublishedKey(String file) throws Exception {
        Path path = Path.of(file);
        if (!Files.exists(path)) {
            return;
        }
        assertThat(Files.readString(path))
                .as("%s must not ship the published signing key, not even as a fallback default", file)
                .doesNotContain(PUBLISHED);
    }

    /**
     * {@code docker-compose.yml} must have no default for JWT_SECRET at all: {@code ${JWT_SECRET:-...}}
     * is what silently handed every deployment the published key.
     */
    @Test
    void dockerComposeRequiresJwtSecretToBeSupplied() throws Exception {
        String compose = Files.readString(Path.of("docker-compose.yml"));

        assertThat(compose)
                .as("compose must refuse to start without JWT_SECRET rather than default it")
                .contains("${JWT_SECRET:?")
                .doesNotContain("${JWT_SECRET:-");
    }
}
