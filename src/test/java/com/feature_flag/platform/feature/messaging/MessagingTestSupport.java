package com.feature_flag.platform.feature.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.feature_flag.platform.feature.events.ChangeType;
import com.feature_flag.platform.feature.events.FeatureChangeEvent;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Shared fixtures for the messaging tests: a canonical event and log capture. */
final class MessagingTestSupport {

    static final String TOPIC = "feature.flag.changes.v1";
    static final String EVENT_ID = "11111111-2222-3333-4444-555555555555";
    static final String TENANT_ID = "42";
    static final String FEATURE_KEY = "checkout_v2";
    static final String ENV_NAME = "prod";
    static final String EXPECTED_KEY = TENANT_ID + ":" + FEATURE_KEY + ":" + ENV_NAME;

    private MessagingTestSupport() {
    }

    static FeatureChangeEvent event() {
        return FeatureChangeEvent.newBuilder()
                .setEventId(EVENT_ID)
                .setChangeType(ChangeType.RULE_ADDED)
                .setTenantId(TENANT_ID)
                .setFeatureKey(FEATURE_KEY)
                .setEnvName(ENV_NAME)
                .setOccurredAt(Instant.ofEpochMilli(1_700_000_000_000L))
                .setActorUserId("user-7")
                .setDetails(Map.of("ruleId", "99"))
                .build();
    }

    /** Attaches a capturing appender to {@code type}'s logger and returns it. */
    static ListAppender<ILoggingEvent> captureLogsOf(Class<?> type) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        return appender;
    }

    static void stopCapturing(Class<?> type, ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
        appender.stop();
    }

    static List<ILoggingEvent> eventsAt(ListAppender<ILoggingEvent> appender, Level level) {
        return appender.list.stream().filter(e -> e.getLevel() == level).toList();
    }
}
