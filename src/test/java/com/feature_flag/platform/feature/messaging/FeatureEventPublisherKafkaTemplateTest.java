package com.feature_flag.platform.feature.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.feature_flag.platform.feature.events.FeatureChangeEvent;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.time.Duration;
import java.util.List;

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

/**
 * The same guarantees exercised through a <em>real</em> {@link KafkaTemplate} over a
 * {@link MockProducer}, so the synchronous-throw path goes through Spring's actual
 * {@code doSend} rather than a stubbed one. No broker, no Testcontainers.
 */
class FeatureEventPublisherKafkaTemplateTest {

    /** {@code KafkaTemplate} closes the producer after each send; keep the recorded history alive. */
    private static final class ReusableMockProducer extends MockProducer<String, FeatureChangeEvent> {
        private ReusableMockProducer() {
            super(true, new StringSerializer(), noopValueSerializer());
        }

        @Override
        public void close() {
            // no-op
        }

        @Override
        public void close(Duration timeout) {
            // no-op
        }
    }

    private static Serializer<FeatureChangeEvent> noopValueSerializer() {
        return (topic, data) -> new byte[0];
    }

    private ReusableMockProducer producer;
    private KafkaTemplate<String, FeatureChangeEvent> template;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        producer = new ReusableMockProducer();
        ProducerFactory<String, FeatureChangeEvent> factory = () -> producer;
        template = new KafkaTemplate<>(factory);
        logs = captureLogsOf(FeatureEventPublisher.class);
    }

    @AfterEach
    void tearDown() {
        stopCapturing(FeatureEventPublisher.class, logs);
    }

    @Test
    @DisplayName("the record reaches the producer on the contract topic and partition key")
    void publishesOneRecordKeyedByTenantFeatureAndEnv() {
        FeatureEventPublisher publisher = new FeatureEventPublisher(template, TOPIC, Runnable::run);

        publisher.publish(event());

        List<ProducerRecord<String, FeatureChangeEvent>> history = producer.history();
        assertThat(history).hasSize(1);
        assertThat(history.get(0).topic()).isEqualTo(TOPIC);
        assertThat(history.get(0).key()).isEqualTo(EXPECTED_KEY);
        assertThat(history.get(0).value().getFeatureKey()).isEqualTo(FEATURE_KEY);
        assertThat(eventsAt(logs, Level.ERROR)).isEmpty();
        assertThat(publisher.getDroppedEventCount()).isZero();
    }

    @Test
    @DisplayName("a producer that throws out of send() loses no diagnostics and no request")
    void serializationFailureInsideTheProducerIsCaughtAndLogged() {
        // Exactly what the Avro/Schema-Registry serializer does when the registry is unreachable
        // or rejects the schema: it throws out of send(), so no future is ever returned.
        producer.sendException = new SerializationException(
                "Error registering Avro schema; error code: 409");

        FeatureEventPublisher publisher = new FeatureEventPublisher(template, TOPIC, Runnable::run);

        assertThatCode(() -> publisher.publish(event())).doesNotThrowAnyException();

        assertThat(producer.history()).isEmpty();
        List<ILoggingEvent> errors = eventsAt(logs, Level.ERROR);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage())
                .contains("FEATURE_EVENT_PUBLISH_FAILED")
                .contains("tenantId=" + TENANT_ID)
                .contains("featureKey=" + FEATURE_KEY);
        assertThat(publisher.getDroppedEventCount()).isEqualTo(1);
    }
}
