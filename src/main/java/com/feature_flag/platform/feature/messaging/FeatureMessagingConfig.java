package com.feature_flag.platform.feature.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Infrastructure for feature-change event publishing: the pool the publish runs on, and the
 * declaration of the topic it publishes to.
 */
@Configuration
public class FeatureMessagingConfig {

    public static final String EXECUTOR_BEAN = "featureEventExecutor";

    /**
     * A pool dedicated to feature-change publishing, deliberately <em>not</em> the shared
     * application {@code taskExecutor}.
     *
     * <p>Two reasons. First, isolation: a stalled broker must not starve unrelated {@code @Async}
     * work (mail, invitations) that shares that pool — Kafka publishing is the one workload here
     * whose latency is controlled by a third party, and the shared pool is small (2 core / 5 max).
     * Second, and decisively, the saturation policy has to differ. {@code CallerRunsPolicy} is the
     * right default for the shared pool, but here the caller <em>is</em> the request thread inside
     * {@code commit()}, still holding a JDBC connection — running the send there on saturation would
     * reproduce exactly the pool-exhaustion outage this work exists to prevent. So this pool uses
     * {@code AbortPolicy}, and {@link FeatureEventPublisher} converts the resulting rejection into a
     * logged, counted dropped event.
     *
     * <p>Sizing: publishing is IO-bound and short (every send is timeout-bounded — see
     * {@code application.properties}), so a small pool with a generous bounded queue absorbs bursts
     * while capping the memory a backlog can hold.
     */
    @Bean(name = EXECUTOR_BEAN)
    public Executor featureEventExecutor(
            @Value("${app.kafka.publisher.core-pool-size:2}") int corePoolSize,
            @Value("${app.kafka.publisher.max-pool-size:4}") int maxPoolSize,
            @Value("${app.kafka.publisher.queue-capacity:500}") int queueCapacity,
            @Value("${app.kafka.publisher.shutdown-await-seconds:10}") int awaitTerminationSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("feature-event-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // Flush what is already queued on shutdown instead of silently binning it.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }

    /**
     * Declares the topic so it is created with the partition count and replication factor the
     * contract specifies, instead of being auto-created by the broker with 1 partition at the
     * broker default replication factor — which would silently destroy per-flag ordering headroom
     * and make {@code acks=all} meaningless.
     *
     * <p>Creation is best-effort: {@code KafkaAdmin} logs and carries on when the broker is
     * unreachable, and the topic is left untouched when it already exists (partition counts are
     * never reduced, and an increase must be done deliberately by an operator). Disable with
     * {@code app.kafka.topic.auto-provision=false} where topics are provisioned by infrastructure
     * code instead.
     */
    @Bean
    @ConditionalOnProperty(name = "app.kafka.topic.auto-provision", havingValue = "true", matchIfMissing = true)
    // Also requires Kafka to actually be configured. Declaring this bean causes Spring's
    // KafkaAdmin to connect at startup, so with no broker configured it logged a full
    // "Could not create admin / You must set either bootstrap.servers" stack trace on every
    // boot — surviving only because spring.kafka.admin.fail-fast is false. The publisher was
    // already gated this way; the topic declaration was not, so disabling Kafka silenced the
    // sends but not the provisioning attempt.
    @ConditionalOnExpression("'${spring.kafka.bootstrap-servers:}' != '' and ${app.kafka.enabled:true}")
    public NewTopic featureChangesTopic(
            @Value("${app.kafka.topic.feature-changes}") String topic,
            @Value("${app.kafka.topic.feature-changes-partitions:6}") int partitions,
            @Value("${app.kafka.topic.feature-changes-replicas:1}") short replicas) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(replicas)
                .build();
    }
}
