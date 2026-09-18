package com.feature_flag.platform.feature.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Configuration is the part of this feature that fails silently, so it is asserted like code.
 *
 * <p>Before this was fixed, {@code spring.kafka.*} existed only in the dev and docker profiles.
 * Spring profiles do not inherit from one another and {@code key-serializer}/{@code value-serializer}
 * have no framework default, so in prod every publish threw
 * {@code ConfigException: Missing required configuration "key.serializer"} — swallowed downstream,
 * behind a 200 OK. These tests pin that every profile resolves a complete, fail-fast producer.
 */
class KafkaProducerConfigurationTest {

    /** Everything a producer must have before it can send a single record. */
    private static final List<String> REQUIRED_PRODUCER_KEYS = List.of(
            "spring.kafka.bootstrap-servers",
            "spring.kafka.properties.schema.registry.url",
            "spring.kafka.producer.key-serializer",
            "spring.kafka.producer.value-serializer",
            "spring.kafka.producer.acks",
            "spring.kafka.producer.properties.enable.idempotence",
            "app.kafka.topic.feature-changes");

    /** Every blocking call the publish makes must be bounded. */
    private static final List<String> REQUIRED_TIMEOUT_KEYS = List.of(
            "spring.kafka.producer.properties.max.block.ms",
            "spring.kafka.producer.properties.request.timeout.ms",
            "spring.kafka.producer.properties.delivery.timeout.ms",
            "spring.kafka.producer.properties.http.connect.timeout.ms",
            "spring.kafka.producer.properties.http.read.timeout.ms");

    private static Properties load(String resource) {
        Properties properties = new Properties();
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            properties.load(in);
        } catch (Exception e) {
            throw new IllegalStateException("cannot read " + resource, e);
        }
        return properties;
    }

    /** What the application actually sees: the profile-less file, overridden by the profile's. */
    private static Map<String, String> effectiveConfig(String profile) {
        Map<String, String> merged = new LinkedHashMap<>();
        load("application.properties").forEach((k, v) -> merged.put((String) k, (String) v));
        load("application-" + profile + ".properties").forEach((k, v) -> merged.put((String) k, (String) v));
        return merged;
    }

    /** {@code ${NAME:default}} -> {@code default}; {@code ${NAME}} -> null (mandatory); literal -> itself. */
    private static String resolvedDefault(String rawValue) {
        if (rawValue == null || !rawValue.startsWith("${") || !rawValue.endsWith("}")) {
            return rawValue;
        }
        String inner = rawValue.substring(2, rawValue.length() - 1);
        int separator = inner.indexOf(':');
        return separator < 0 ? null : inner.substring(separator + 1);
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"dev", "docker", "staging", "prod"})
    @DisplayName("every profile resolves a complete producer configuration")
    void everyProfileHasACompleteProducerConfiguration(String profile) {
        Map<String, String> config = effectiveConfig(profile);

        assertThat(config).containsKeys(REQUIRED_PRODUCER_KEYS.toArray(String[]::new));
        assertThat(config).containsKeys(REQUIRED_TIMEOUT_KEYS.toArray(String[]::new));
        assertThat(config.get("spring.kafka.producer.key-serializer"))
                .isEqualTo("org.apache.kafka.common.serialization.StringSerializer");
        assertThat(config.get("spring.kafka.producer.value-serializer"))
                .isEqualTo("io.confluent.kafka.serializers.KafkaAvroSerializer");
        assertThat(resolvedDefault(config.get("spring.kafka.producer.acks")))
                .as("durability: the contract's at-least-once story needs every in-sync replica")
                .isEqualTo("all");
        assertThat(resolvedDefault(config.get("spring.kafka.producer.properties.enable.idempotence")))
                .isEqualTo("true");
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"dev", "docker", "staging", "prod"})
    @DisplayName("every blocking call is bounded well below the Kafka/registry 60s defaults")
    void everyProfileBoundsEveryBlockingCall(String profile) {
        Map<String, String> config = effectiveConfig(profile);

        for (String key : REQUIRED_TIMEOUT_KEYS) {
            String value = resolvedDefault(config.get(key));
            assertThat(value).as("%s must have a value or a default", key).isNotNull();
            int millis = Integer.parseInt(value.trim());
            assertThat(millis).as("%s must be positive", key).isPositive();
            assertThat(millis)
                    .as("%s must stay far below the 60s defaults — the publish holds a pool thread", key)
                    .isLessThanOrEqualTo(15_000);
        }
        // Kafka requires delivery.timeout.ms >= linger.ms + request.timeout.ms, else the producer
        // refuses to start at all.
        int linger = Integer.parseInt(resolvedDefault(config.get("spring.kafka.producer.properties.linger.ms")));
        int request = Integer.parseInt(
                resolvedDefault(config.get("spring.kafka.producer.properties.request.timeout.ms")));
        int delivery = Integer.parseInt(
                resolvedDefault(config.get("spring.kafka.producer.properties.delivery.timeout.ms")));
        assertThat(delivery).isGreaterThanOrEqualTo(linger + request);
    }

    @ParameterizedTest(name = "profile {0}")
    @ValueSource(strings = {"staging", "prod"})
    @DisplayName("prod and staging demand their Kafka endpoints from the environment, like JWT_SECRET")
    void deployedProfilesHaveNoLocalhostFallback(String profile) {
        Properties profileFile = load("application-" + profile + ".properties");

        for (String key : List.of("spring.kafka.bootstrap-servers",
                "spring.kafka.properties.schema.registry.url")) {
            String raw = profileFile.getProperty(key);
            assertThat(raw).as("%s must be declared in %s", key, profile).isNotNull();
            assertThat(raw).matches("\\$\\{[A-Z0-9_]+}");
            assertThat(resolvedDefault(raw))
                    .as("%s must fail startup when unset rather than silently pointing at localhost", key)
                    .isNull();
        }
    }

    @Test
    @DisplayName("the topic is declared with the partition count and replication factor the contract specifies")
    void topicShapeMatchesTheContract() {
        Map<String, String> base = effectiveConfig("dev");
        assertThat(resolvedDefault(base.get("app.kafka.topic.feature-changes-partitions"))).isEqualTo("6");
        assertThat(resolvedDefault(base.get("app.kafka.topic.feature-changes-replicas"))).isEqualTo("1");

        Map<String, String> prod = effectiveConfig("prod");
        assertThat(resolvedDefault(prod.get("app.kafka.topic.feature-changes-replicas")))
                .as("acks=all is meaningless on a single replica")
                .isEqualTo("3");
        assertThat(prod.get("app.kafka.topic.feature-changes")).isEqualTo("feature.flag.changes.v1");
    }
}
