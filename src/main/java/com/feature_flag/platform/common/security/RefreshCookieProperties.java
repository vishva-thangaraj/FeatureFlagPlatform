package com.feature_flag.platform.common.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * How the refresh-token cookie is written, for clients that ask for one.
 */
@Component
@ConfigurationProperties(prefix = "app.security.refresh-cookie")
@Getter
@Setter
public class RefreshCookieProperties {

    private String name = "refresh_token";

    /**
     * Scoped to the auth endpoints, so the browser attaches a seven-day credential only to the two calls
     * that need it instead of to every request the dashboard makes. Narrowing the path is most of the
     * value of the cookie: a credential that is not sent is a credential that cannot be stolen in transit
     * or replayed by a confused endpoint.
     */
    private String path = "/api/v1/auth";

    /**
     * {@code Secure} keeps the cookie off plaintext connections. It defaults to on, and turning it off
     * means a seven-day credential crosses the network in clear text on every refresh — acceptable while
     * developing against {@code http://localhost}, never acceptable anywhere else. Note that browsers
     * exempt {@code localhost} from the Secure requirement, so local development usually does not need
     * this at all; a deployment that does is a deployment that needs TLS.
     */
    private boolean secure = true;

    /**
     * {@code Strict}, not {@code Lax}: the dashboard is served from the same origin as the API, so the
     * cookie never needs to survive a cross-site navigation, and Strict is what removes the CSRF surface
     * that moving a credential into a cookie would otherwise reintroduce.
     */
    private String sameSite = "Strict";
}
