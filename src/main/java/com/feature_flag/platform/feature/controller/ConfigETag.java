package com.feature_flag.platform.feature.controller;

import java.time.Instant;

/**
 * Entity tags for the flag-configuration endpoints, and the {@code If-None-Match} comparison that goes
 * with them. Shared by the dashboard and SDK controllers so there is one implementation of RFC 7232 §3.2
 * rather than two that drift.
 */
final class ConfigETag {

    private ConfigETag() {
    }

    /** Tag for one tenant's view of a resource, where the URL alone already identifies it. */
    static String of(Long tenantId, Instant lastModified) {
        return "\"" + tenantId + "-" + epochMillis(lastModified) + "\"";
    }

    /**
     * Tag for a resource whose environment is not in the URL — the SDK path, where the key decides it.
     * Without the environment, two of a tenant's environments sharing a modification timestamp would
     * produce the same tag for the same URL.
     */
    static String of(Long tenantId, Long envId, Instant lastModified) {
        return "\"" + tenantId + "-" + envId + "-" + epochMillis(lastModified) + "\"";
    }

    /**
     * {@code If-None-Match} per RFC 7232 §3.2: a comma-separated list of entity tags, or {@code *},
     * compared with the <em>weak</em> comparison function — {@code W/"x"} and {@code "x"} match.
     *
     * <p>A plain {@code equals} against the raw header missed all of it: {@code W/"42-1"} sent by a proxy
     * that weakened the validator, a client listing several cached tags, and {@code *}. Spring's own
     * conditional-request check in {@code HttpEntityMethodProcessor} happens to catch the first two on the
     * way out, so the only visible symptom was {@code *} — but a 304 decision taken explicitly should be
     * right on its own terms, not right by accident downstream.
     */
    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        String header = ifNoneMatch.trim();
        if ("*".equals(header)) {
            return true;
        }
        for (String candidate : header.split(",")) {
            if (opaqueTag(candidate).equals(opaqueTag(etag))) {
                return true;
            }
        }
        return false;
    }

    /** Strips surrounding whitespace and the weak-validator {@code W/} prefix. */
    private static String opaqueTag(String tag) {
        String trimmed = tag.trim();
        return trimmed.startsWith("W/") ? trimmed.substring(2).trim() : trimmed;
    }

    private static long epochMillis(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }
}
