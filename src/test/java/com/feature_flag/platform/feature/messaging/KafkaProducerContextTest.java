package com.feature_flag.platform.feature.messaging;

import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the property layering actually reaches the framework: the producer Spring builds has the
 * required serializers, the durability settings and a bounded {@code max.block.ms} — the things
 * whose absence used to surface only as a swallowed exception in production.
 */
@SpringBootTest
@ActiveProfiles("test")
class KafkaProducerContextTest {

    @Autowired private ProducerFactory<?, ?> producerFactory;
    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("the producer Spring builds is complete, durable and time-bounded")
    void producerConfigurationIsCompleteAndBounded() {
        Map<String, Object> config = producerFactory.getConfigurationProperties();

        assertThat(config).containsEntry("key.serializer", StringSerializer.class);
        assertThat(config).containsEntry("value.serializer", KafkaAvroSerializer.class);
        assertThat(config).containsEntry("acks", "all");
        assertThat(config).containsEntry("enable.idempotence", "true");
        assertThat(config).containsKeys("max.block.ms", "request.timeout.ms", "delivery.timeout.ms",
                "http.connect.timeout.ms", "http.read.timeout.ms", "schema.registry.url");
        assertThat(Integer.parseInt(config.get("max.block.ms").toString()))
                .as("send() must not be able to park a thread for the 60s Kafka default")
                .isLessThanOrEqualTo(15_000);
    }

    @Test
    @DisplayName("publishing runs on its own dedicated pool, not the shared application executor")
    void publishingHasItsOwnExecutor() {
        Object executor = context.getBean(FeatureMessagingConfig.EXECUTOR_BEAN);

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        assertThat(((ThreadPoolTaskExecutor) executor).getThreadNamePrefix()).isEqualTo("feature-event-");
        assertThat(executor).isNotSameAs(context.getBean("taskExecutor"));
    }

    @Test
    @DisplayName("topic provisioning is declared with the contract's shape and can be switched off")
    void topicIsDeclaredWithTheContractShape() {
        NewTopic topic = new FeatureMessagingConfig()
                .featureChangesTopic("feature.flag.changes.v1", 6, (short) 3);

        assertThat(topic.name()).isEqualTo("feature.flag.changes.v1");
        assertThat(topic.numPartitions()).isEqualTo(6);
        assertThat(topic.replicationFactor()).isEqualTo((short) 3);
        // The test profile sets app.kafka.topic.auto-provision=false, so no bean is registered and
        // no context load reaches out to a broker.
        assertThat(context.getBeansOfType(NewTopic.class)).isEmpty();
    }
}
