package com.feature_flag.platform.feature.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.feature_flag.platform.feature.events.FeatureChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.List;

import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.FEATURE_KEY;
import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.TENANT_ID;
import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.captureLogsOf;
import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.event;
import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.eventsAt;
import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.stopCapturing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FeatureEventListenerTest {

    private FeatureEventPublisher publisher;
    private FeatureEventListener listener;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        publisher = mock(FeatureEventPublisher.class);
        listener = new FeatureEventListener(publisher);
        logs = captureLogsOf(FeatureEventListener.class);
    }

    @AfterEach
    void tearDown() {
        stopCapturing(FeatureEventListener.class, logs);
    }

    @Test
    @DisplayName("publishing still happens after commit, and quietly, on the normal path")
    void listensAfterCommitWithFallbackExecutionEnabled() throws Exception {
        Method method = FeatureEventListener.class.getMethod("publishEvent", FeatureChanged.class);
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase())
                .as("a consumer must never see a change the database rolled back")
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution())
                .as("the default (false) discards the event with only a DEBUG line if a write path "
                        + "ever loses its @Transactional — the failure must be loud")
                .isTrue();
    }

    @Test
    @DisplayName("an event raised outside a transaction is published with a loud warning, not discarded")
    void warnsLoudlyWhenInvokedOutsideATransaction() {
        FeatureChangeEvent event = event();

        listener.publishEvent(new FeatureChanged(event));

        verify(publisher).publish(event);
        List<ILoggingEvent> warnings = eventsAt(logs, Level.WARN);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getFormattedMessage())
                .contains("outside a transaction")
                .contains("tenantId=" + TENANT_ID)
                .contains("featureKey=" + FEATURE_KEY);
    }
}
