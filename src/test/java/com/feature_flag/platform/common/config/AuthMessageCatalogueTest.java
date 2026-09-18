package com.feature_flag.platform.common.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guards the auth message catalogue itself, which the service-level tests cannot reach: they run with
 * {@link I18N} mocked to echo the key, so the text a real caller receives is only ever asserted here.
 */
class AuthMessageCatalogueTest {

    private static Properties messages;

    @BeforeAll
    static void loadCatalogue() throws IOException {
        messages = new Properties();
        try (InputStream in = AuthMessageCatalogueTest.class.getResourceAsStream("/messages.properties")) {
            assertNotNull(in, "messages.properties must be on the classpath");
            messages.load(in);
        }
    }

    /**
     * A placeholder is the mechanism by which the probed address got echoed back in the first place.
     * No placeholder, nothing to interpolate, nothing to leak — even if a caller later tries to pass
     * arguments to this key.
     */
    @Test
    void registrationConflictMessage_hasNoPlaceholderToEchoTheProbedAddress() {
        String text = messages.getProperty(Messages.AUTH_REGISTRATION_CONFLICT);
        assertNotNull(text, Messages.AUTH_REGISTRATION_CONFLICT + " must be defined");
        assertFalse(text.contains("{"),
                "registration conflict text must take no arguments, was: " + text);
    }

    /**
     * /api/v1/auth/register is unauthenticated, so this text is read by anonymous probers. It may
     * point a genuine user at sign-in or password reset, but it must not state as fact that the
     * submitted address is registered.
     */
    @Test
    void registrationConflictMessage_doesNotConfirmThatTheAddressIsRegistered() {
        String text = messages.getProperty(Messages.AUTH_REGISTRATION_CONFLICT).toLowerCase(Locale.ROOT);
        for (String confirmation : List.of(
                "already in use", "already exists", "already registered", "is taken", "email already")) {
            assertFalse(text.contains(confirmation),
                    "registration conflict text must not confirm the address exists, found \""
                            + confirmation + "\" in: " + text);
        }
    }

    /**
     * The old key interpolated the probed address. Leaving it in the catalogue invites somebody to
     * wire it back up, so its absence is asserted rather than assumed.
     */
    @Test
    void theEchoingRegistrationMessageIsGoneFromTheCatalogue() {
        assertNull(messages.getProperty("auth.email.already.exists"),
                "auth.email.already.exists echoed the probed address and must stay deleted");
    }

    /** Non-ACTIVE, non-SUSPENDED accounts need a message of their own rather than borrowing one. */
    @Test
    void inactiveAccountMessageIsDefinedAndDistinctFromSuspension() {
        String notActive = messages.getProperty(Messages.AUTH_USER_NOT_ACTIVE);
        assertNotNull(notActive, Messages.AUTH_USER_NOT_ACTIVE + " must be defined");
        assertFalse(notActive.equals(messages.getProperty(Messages.AUTH_USER_SUSPENDED)),
                "an inactive account is not a suspended one");
    }
}
