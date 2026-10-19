package com.feature_flag.platform.common.security;

import com.feature_flag.platform.auth.dto.AuthResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * Carries the refresh token in an {@code HttpOnly} cookie for clients that ask for one.
 *
 * <h2>What this does and does not buy</h2>
 *
 * <p>In the body, the refresh token has to live somewhere JavaScript can reach — in practice
 * {@code localStorage} — so one XSS bug hands an attacker a seven-day credential they can carry off and
 * use from their own machine, long after the victim has closed the tab, rotating it indefinitely.
 *
 * <p>An {@code HttpOnly} cookie does <strong>not</strong> stop XSS from using the session: script on the
 * page can still call {@code /refresh}, because the browser attaches the cookie for it. What it stops is
 * <em>exfiltration</em> — the attacker is confined to the victim's browser, for as long as the page is
 * open, instead of walking away with the credential. That is the whole claim, and it is worth making;
 * anything stronger would be overstating it.
 *
 * <p>That is also why opting in <strong>removes the token from the response body</strong> rather than
 * sending it in both places. A body that still carries it leaves script free to call {@code /refresh},
 * read the rotated token out of the response and exfiltrate that instead — which would leave the cookie
 * buying nothing at all.
 *
 * <h2>Opting in</h2>
 *
 * <p>Clients that cannot hold cookies — an SDK, a CLI, a server-side integration — must keep getting the
 * token in the body, so cookie transport is requested, never imposed:
 *
 * <ul>
 *   <li>Send {@code X-Refresh-Transport: cookie} on register, login or switch-tenant.</li>
 *   <li>On refresh and logout the cookie's own presence is the request, so a browser needs no header and
 *       no body at all.</li>
 * </ul>
 */
@Component
public class RefreshTokenCookies {

    /** Request header a client sends to be given a cookie instead of a body field. */
    public static final String TRANSPORT_HEADER = "X-Refresh-Transport";

    private static final String COOKIE_TRANSPORT = "cookie";

    private final RefreshCookieProperties properties;
    private final JwtProperties jwtProperties;

    public RefreshTokenCookies(RefreshCookieProperties properties, JwtProperties jwtProperties) {
        this.properties = properties;
        this.jwtProperties = jwtProperties;
    }

    /**
     * Whether this caller wants the token in a cookie.
     *
     * <p>A request that already carries the cookie counts as asking: it is how a browser's refresh and
     * logout calls identify themselves, without having to remember a header on every call.
     */
    public boolean wanted(HttpServletRequest request) {
        return COOKIE_TRANSPORT.equalsIgnoreCase(request.getHeader(TRANSPORT_HEADER))
                || read(request).isPresent();
    }

    /** The refresh token this request carries in its cookie, if any. */
    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> properties.getName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    /**
     * Issues the cookie and returns the response with the token stripped from the body.
     *
     * <p>For a caller that did not ask, both are left alone — this is the path every existing client is
     * on, and it behaves exactly as it did before.
     */
    public AuthResponse apply(AuthResponse authResponse, HttpServletRequest request,
                              HttpServletResponse response) {
        if (authResponse == null || !wanted(request)) {
            return authResponse;
        }
        set(response, authResponse.refreshToken());
        return withoutRefreshToken(authResponse);
    }

    /** A copy carrying no refresh token; {@code AuthResponse} omits the field entirely when it is null. */
    public AuthResponse withoutRefreshToken(AuthResponse authResponse) {
        return new AuthResponse(
                authResponse.accessToken(),
                null,
                authResponse.userId(),
                authResponse.email(),
                authResponse.tenantId(),
                authResponse.roles(),
                authResponse.accessTokenExpiresIn());
    }

    private void set(HttpServletResponse response, String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(rawToken)
                .maxAge(Duration.ofMillis(jwtProperties.getRefreshTokenExpirationMs()))
                .build()
                .toString());
    }

    /**
     * Expires the cookie.
     *
     * <p>Written with the same name, path and attributes as the one it replaces: a browser matches a
     * removal to an existing cookie by name <em>and</em> path, so clearing it from a different path would
     * leave the original in place and the user still holding a revoked token on disk.
     */
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("").maxAge(0).build().toString());
    }

    private ResponseCookie.ResponseCookieBuilder cookie(String value) {
        return ResponseCookie.from(properties.getName(), value)
                .httpOnly(true)
                .secure(properties.isSecure())
                .sameSite(properties.getSameSite())
                .path(properties.getPath());
    }
}
