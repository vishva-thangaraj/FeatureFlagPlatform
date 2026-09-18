package com.feature_flag.platform.feature.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.feature_flag.platform.feature.events.FeatureChangeEvent;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.ENV_NAME;
import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.EVENT_ID;
import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.EXPECTED_KEY;
import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.FEATURE_KEY;
import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.TENANT_ID;
import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.TOPIC;
import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.captureLogsOf;
import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.event;
import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.eventsAt;
import static com.feature_flag.platform.feature.messaging.MessagingTestSupport.stopCapturing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The publisher's job is to make a Kafka problem a *logged* problem, never a caller's problem:
 * the flag change has already committed and the API has already answered 200 OK by the time it runs.
 */
class FeatureEventPublisherTest {

    private KafkaTemplate<String, FeatureChangeEvent> kafka;
    private SendResult<String, FeatureChangeEvent> sendResult;
    private ListAppender<ILoggingEvent> logs;
    private ThreadPoolTaskExecutor pool;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        sendResult = mock(SendResult.class);
        logs = captureLogsOf(FeatureEventPublisher.class);
    }

    @AfterEach
    void tearDown() {
        stopCapturing(FeatureEventPublisher.class, logs);
        if (pool != null) {
            pool.shutdown();
        }
    }

    private FeatureEventPublisher publisherOn(Executor executor) {
        return new FeatureEventPublisher(kafka, TOPIC, executor);
    }

    /** Runs the task inline so the failure paths can be asserted deterministically. */
    private static Executor sameThread() {
        return Runnable::run;
    }

    private ThreadPoolTaskExecutor realPool(int core, int max, int queue) {
        pool = (ThreadPoolTaskExecutor) new FeatureMessagingConfig()
                .featureEventExecutor(core, max, queue, 1);
        return pool;
    }

    private ILoggingEvent singleLossLog() {
        List<ILoggingEvent> errors = eventsAt(logs, Level.ERROR);
        assertThat(errors).hasSize(1);
        return errors.get(0);
    }

    private void assertCarriesFullContext(ILoggingEvent logged) {
        assertThat(logged.getFormattedMessage())
                .contains("FEATURE_EVENT_PUBLISH_FAILED")
                .contains("tenantId=" + TENANT_ID)
                .contains("featureKey=" + FEATURE_KEY)
                .contains("envName=" + ENV_NAME)
                .contains("changeType=RULE_ADDED")
                .contains("eventId=" + EVENT_ID)
                .contains("partitionKey=" + EXPECTED_KEY)
                .contains("topic=" + TOPIC);
        assertThat(logged.getThrowableProxy())
                .as("the cause must be attached so the stack trace is diagnosable")
                .isNotNull();
    }

    @Test
    @DisplayName("a serializer blowing up inside send() is caught and logged with tenant/feature context")
    void synchronousSerializationFailureIsCaughtAndLoggedWithContext() {
        // KafkaTemplate.doSend does not wrap serialization, so this escapes send() itself:
        // no future is returned and the whenComplete callback is never attached.
        when(kafka.send(anyString(), anyString(), any()))
                .thenThrow(new SerializationException("Error serializing Avro message"));

        FeatureEventPublisher publisher = publisherOn(sameThread());

        assertThatCode(() -> publisher.publish(event())).doesNotThrowAnyException();

        assertCarriesFullContext(singleLossLog());
        assertThat(publisher.getDroppedEventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a missing producer configuration is caught, not thrown into Spring's afterCompletion")
    void synchronousConfigExceptionIsCaughtAndLogged() {
        // The prod/staging failure mode before the config fix: ConfigException out of producer creation.
        when(kafka.send(anyString(), anyString(), any()))
                .thenThrow(new ConfigException("Missing required configuration \"key.serializer\""));

        FeatureEventPublisher publisher = publisherOn(sameThread());

        assertThatCode(() -> publisher.publish(event())).doesNotThrowAnyException();

        assertCarriesFullContext(singleLossLog());
        assertThat(publisher.getDroppedEventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("an asynchronous broker failure is logged with the same context")
    void asynchronousSendFailureIsLoggedWithContext() {
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new TimeoutException("Topic feature.flag.changes.v1 not present in metadata")));

        FeatureEventPublisher publisher = publisherOn(sameThread());
        publisher.publish(event());

        assertCarriesFullContext(singleLossLog());
        assertThat(publisher.getDroppedEventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a successful publish logs no error and uses the contract partition key")
    void successfulPublishUsesTenantFeatureEnvKeyAndLogsNoError() {
        when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(sendResult));

        FeatureEventPublisher publisher = publisherOn(sameThread());
        publisher.publish(event());

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(topic.capture(), key.capture(), any());
        assertThat(topic.getValue()).isEqualTo(TOPIC);
        assertThat(key.getValue()).isEqualTo(EXPECTED_KEY);
        assertThat(eventsAt(logs, Level.ERROR)).isEmpty();
        assertThat(publisher.getDroppedEventCount()).isZero();
    }

    @Test
    @DisplayName("publish returns immediately and sends on the publisher pool, never on the caller")
    void publishDoesNotBlockTheCallingThread() throws Exception {
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        AtomicReference<String> sendingThread = new AtomicReference<>();
        when(kafka.send(anyString(), anyString(), any())).thenAnswer(invocation -> {
            sendingThread.set(Thread.currentThread().getName());
            sendEntered.countDown();
            // Stands in for KafkaProducer blocking in waitOnMetadata for max.block.ms.
            releaseSend.await(10, TimeUnit.SECONDS);
            return CompletableFuture.completedFuture(sendResult);
        });

        FeatureEventPublisher publisher = publisherOn(realPool(2, 4, 500));
        String callerThread = Thread.currentThread().getName();

        long startedAt = System.nanoTime();
        publisher.publish(event());
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        try {
            assertThat(elapsedMillis)
                    .as("publish must hand off, not wait on the broker — it runs inside commit() "
                            + "on the request thread, holding a JDBC connection")
                    .isLessThan(1_000);
            assertThat(sendEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(sendingThread.get()).startsWith("feature-event-").isNotEqualTo(callerThread);
        } finally {
            releaseSend.countDown();
        }
    }

    @Test
    @DisplayName("when the publish queue is saturated the event is dropped and logged, never run on the caller")
    void saturatedPoolDropsTheEventInsteadOfRunningOnTheCallingThread() throws Exception {
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        AtomicReference<String> sendingThread = new AtomicReference<>();
        when(kafka.send(anyString(), anyString(), any())).thenAnswer(invocation -> {
            sendingThread.set(Thread.currentThread().getName());
            sendEntered.countDown();
            releaseSend.await(10, TimeUnit.SECONDS);
            return CompletableFuture.completedFuture(sendResult);
        });

        // One worker, one queue slot: the third publish has nowhere to go.
        FeatureEventPublisher publisher = publisherOn(realPool(1, 1, 1));
        String callerThread = Thread.currentThread().getName();

        try {
            publisher.publish(event());                       // occupies the worker
            assertThat(sendEntered.await(10, TimeUnit.SECONDS)).isTrue();
            publisher.publish(event());                       // fills the queue
            publisher.publish(event());                       // rejected

            assertThat(publisher.getDroppedEventCount()).isEqualTo(1);
            ILoggingEvent logged = singleLossLog();
            assertThat(logged.getFormattedMessage()).contains("publish queue is full");
            assertCarriesFullContext(logged);
            assertThat(sendingThread.get())
                    .as("AbortPolicy, not CallerRunsPolicy: saturation must never push the send "
                            + "back onto the request thread")
                    .isNotEqualTo(callerThread);
        } finally {
            releaseSend.countDown();
        }
    }

    @Test
    @DisplayName("an executor rejection is reported as a lost event rather than propagating")
    void rejectedExecutionIsReportedAsALostEvent() {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("pool saturated");
        };

        FeatureEventPublisher publisher = publisherOn(rejecting);

        assertThatCode(() -> publisher.publish(event())).doesNotThrowAnyException();
        assertCarriesFullContext(singleLossLog());
        assertThat(publisher.getDroppedEventCount()).isEqualTo(1);
    }
}
