package com.feature_flag.platform.feature.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deployment with no broker configured must start cleanly — not merely survive.
 *
 * <p>The publisher was gated on Kafka being configured, but the {@code NewTopic} declaration was
 * not. Declaring that bean makes Spring's {@code KafkaAdmin} connect during startup, so a
 * deployment that had deliberately left Kafka unset still logged a full
 * <em>"Could not create admin / You must set either bootstrap.servers"</em> stack trace on every
 * boot. It only looked survivable because {@code spring.kafka.admin.fail-fast=false} downgrades the
 * failure to an error log — which is exactly the kind of noise that buries a real one.
 *
 * <p>Empty bootstrap servers is the supported "Kafka is off" configuration for prod and staging, so
 * it has to be quiet.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=",
        "spring.kafka.properties.schema.registry.url=",
        "app.kafka.topic.auto-provision=true"
})
@ActiveProfiles("test")
class KafkaDisabledContextTest {

    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("no topic is declared when no broker is configured, so KafkaAdmin never connects")
    void noTopicBeanWithoutABroker() {
        assertThat(context.getBeansOfType(NewTopic.class))
                .as("a NewTopic bean makes KafkaAdmin dial the broker at startup; with none "
                        + "configured that is a guaranteed error on every boot")
                .isEmpty();
    }

    /**
     * The application still has to come up and serve: disabling an unconsumed notification channel
     * must not take the API with it.
     */
    @Test
    @DisplayName("the application context still starts with Kafka switched off")
    void contextStartsAnyway() {
        assertThat(context.containsBean("featureEventPublisher")).isTrue();
        assertThat(context.containsBean(FeatureMessagingConfig.EXECUTOR_BEAN)).isTrue();
    }
}
