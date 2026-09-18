package com.feature_flag.platform.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides - and refuses - the HS256 signing secret at startup.
 *
 * <p>The secret used to be committed twice: as the {@code JWT_SECRET} default in {@code docker-compose.yml}
 * and as the {@code jwt.secret} default of the {@code dev} profile, which is also the profile the app falls
 * back to when {@code SPRING_PROFILES_ACTIVE} is unset. Anyone who could read the repository could mint an
 * HS256 token with any {@code sub}, {@code tenantId} and {@code roles:["OWNER"]} they liked, and a
 * deployment that simply forgot to export the variable inherited that key silently. Both defaults are gone;
 * this class is what stops them coming back.
 *
 * <p>The rules:
 * <ul>
 *   <li>A secret that has ever been published - the key that was committed to this repository - is rejected
 *       in <em>every</em> profile. It is public knowledge; there is no environment where it is acceptable.</li>
 *   <li>A secret shorter than 256 bits is rejected in every profile: HS256 is specified for a key at least
 *       as long as its output, and a short key is brute-forceable offline from a single captured token.</li>
 *   <li>Outside the non-production profiles, a missing secret is a startup failure. Never a default, never a
 *       generated one - two nodes generating different keys would reject each other's tokens, which fails
 *       confusingly rather than loudly.</li>
 *   <li>Inside them ({@code dev}, {@code test}, {@code local}), a missing secret is filled with a random one
 *       generated per boot, so a developer needs no setup and nothing forgeable is ever written down.</li>
 * </ul>
 */
@Component
public class JwtSecretPolicy {

    private static final Logger log = LoggerFactory.getLogger(JwtSecretPolicy.class);

    /** HS256's own output length. A key shorter than this weakens the MAC and jjwt rejects it anyway. */
    static final int MIN_SECRET_BYTES = 32;

    /** Profiles where a developer convenience is acceptable because no real data is behind the app. */
    static final Set<String> NON_PRODUCTION_PROFILES = Set.of("dev", "test", "local");

    /**
     * Secrets that are public knowledge. The first entry is the key this repository shipped as the
     * {@code dev} and {@code docker-compose} default; add to this list, never remove from it.
     */
    static final Set<String> PUBLISHED_SECRETS = Set.of(
            "dGhpcy1pcy1hLWRldi1vbmx5LWp3dC1zZWNyZXQta2V5LTMyYg==");

    private final String secret;

    @Autowired
    public JwtSecretPolicy(JwtProperties jwtProperties, Environment environment) {
        this(jwtProperties.getSecret(), activeProfiles(environment));
        // The provider reads its key through this bean, but JwtProperties is injected elsewhere too;
        // keep the two in step so nothing can read the raw, unvalidated value.
        jwtProperties.setSecret(this.secret);
    }

    // Visible for testing: lets a test drive the profile set without a Spring context.
    JwtSecretPolicy(String configuredSecret, List<String> activeProfiles) {
        this.secret = resolve(configuredSecret, activeProfiles);
    }

    /** The validated secret every signing key must be built from. */
    public String getSecret() {
        return secret;
    }

    private static List<String> activeProfiles(Environment environment) {
        String[] active = environment.getActiveProfiles();
        return Arrays.asList(active.length > 0 ? active : environment.getDefaultProfiles());
    }

    static String resolve(String configuredSecret, List<String> activeProfiles) {
        boolean nonProduction = isNonProduction(activeProfiles);
        String trimmed = configuredSecret == null ? "" : configuredSecret.trim();

        if (trimmed.isEmpty()) {
            if (!nonProduction) {
                throw refuse("no JWT secret is configured. Set the JWT_SECRET environment variable to a"
                        + " freshly generated value (openssl rand -base64 32).", activeProfiles);
            }
            String generated = generateSecret();
            log.warn("No JWT_SECRET configured; generated an EPHEMERAL signing key for profile(s) {}."
                    + " Tokens will not survive a restart and will not be accepted by any other instance."
                    + " Set JWT_SECRET to pin one.", activeProfiles);
            return generated;
        }

        if (PUBLISHED_SECRETS.contains(trimmed)) {
            // Unconditional: this value was committed to a source repository, so it is compromised in
            // every environment, including a developer's laptop.
            throw refuse("the configured JWT secret is a publicly published value that was once committed"
                    + " to this repository. Generate a new one (openssl rand -base64 32).", activeProfiles);
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException e) {
            throw refuse("the configured JWT secret is not valid base64. Generate one with"
                    + " 'openssl rand -base64 32'.", activeProfiles);
        }
        if (decoded.length < MIN_SECRET_BYTES) {
            throw refuse("the configured JWT secret decodes to " + decoded.length + " bytes; HS256 requires"
                    + " at least " + MIN_SECRET_BYTES + " (openssl rand -base64 32).", activeProfiles);
        }

        return trimmed;
    }

    private static boolean isNonProduction(List<String> activeProfiles) {
        // Fail closed: an empty or unrecognised profile set is treated as production. A profile list that
        // mixes 'dev' with anything else is production too - the strictest member wins.
        if (activeProfiles == null || activeProfiles.isEmpty()) {
            return false;
        }
        return activeProfiles.stream()
                .map(p -> p == null ? "" : p.trim().toLowerCase())
                .allMatch(NON_PRODUCTION_PROFILES::contains);
    }

    private static String generateSecret() {
        byte[] bytes = new byte[MIN_SECRET_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static IllegalStateException refuse(String detail, List<String> activeProfiles) {
        return new IllegalStateException("Refusing to start with profile(s) "
                + new LinkedHashSet<>(activeProfiles) + ": " + detail);
    }
}
