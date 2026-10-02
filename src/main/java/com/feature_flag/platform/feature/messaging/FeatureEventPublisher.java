package com.feature_flag.platform.feature.messaging;

import com.feature_flag.platform.feature.events.FeatureChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes {@link FeatureChangeEvent}s to Kafka, off the request thread and without ever
 * letting a messaging failure reach the caller.
 *
 * <p><b>Why the work is handed to an executor.</b> The only caller is
 * {@link FeatureEventListener}, which runs in {@code AFTER_COMMIT}. That callback executes
 * synchronously inside {@code commit()}, while the JPA {@code EntityManager} and its pooled JDBC
 * connection are still bound to the thread. {@code KafkaProducer.send()} blocks in
 * {@code waitOnMetadata} for up to {@code max.block.ms} and the schema-registry client performs a
 * blocking HTTP call, so publishing inline would park a Tomcat worker <em>and</em> a HikariCP
 * connection for the whole of a broker outage. Moving the send to a bounded pool keeps an optional
 * notification from taking the API down with it. Timeouts (see {@code application.properties}) are
 * the second half of that guarantee: they bound how long a pool thread can be held.
 *
 * <p><b>Why every failure is caught here.</b> {@code KafkaTemplate.doSend} does not wrap producer
 * creation, metadata lookup or serialization in a try block, so {@code ConfigException},
 * {@code TimeoutException} and Avro/Schema-Registry {@code SerializationException} are thrown
 * <em>synchronously</em> out of {@code send()} — no future is ever returned and the
 * {@code whenComplete} callback never runs. Those are exactly the broker-down and registry-down
 * cases. Both the synchronous throw and the asynchronous completion are therefore funnelled into
 * {@link #recordLoss}.
 *
 * <p><b>Delivery guarantee.</b> This is at-most-once with <em>loud, diagnosable</em> loss, not the
 * at-least-once the contract used to claim. A transactional outbox would be the real fix; it is a
 * deliberate follow-up (see docs/feature-events.md) rather than something to land here, because it
 * needs its own table, a relay/poller, ordering and idempotency work — and there is not yet a single
 * consumer to benefit from it. What is implemented instead is the smallest change that stops loss
 * from being silent: one ERROR per lost event carrying every field needed to replay it by hand, and
 * a process-wide counter of dropped events.
 */
@Component
public class FeatureEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(FeatureEventPublisher.class);

    /** Stable, greppable marker for alerting rules — do not reword. */
    private static final String LOSS_MARKER = "FEATURE_EVENT_PUBLISH_FAILED";

    private final KafkaTemplate<String, FeatureChangeEvent> kafka;
    private final String topic;
    private final Executor executor;
    private final boolean publishingEnabled;
    private final AtomicLong droppedEvents = new AtomicLong();

    public FeatureEventPublisher(KafkaTemplate<String, FeatureChangeEvent> kafka,
                                 @Value("${app.kafka.topic.feature-changes}") String topic,
                                 @Value("${spring.kafka.bootstrap-servers:}") String bootstrapServers,
                                 @Value("${app.kafka.enabled:true}") boolean enabled,
                                 @Qualifier(FeatureMessagingConfig.EXECUTOR_BEAN) Executor executor) {
        this.kafka = kafka;
        this.topic = topic;
        this.executor = executor;
        this.publishingEnabled = enabled && bootstrapServers != null && !bootstrapServers.isBlank();

        if (!this.publishingEnabled) {
            // Deliberately ERROR, and deliberately not a startup failure. Making an unconsumed,
            // best-effort notification channel a hard boot dependency would let a missing broker take
            // the whole API down — including the read paths SDKs depend on — which is a worse outcome
            // than losing events. The counter and the per-event ERROR below keep the loss measurable.
            LOGGER.error("{}: feature-change event publishing is DISABLED — {}. Flag changes will still be "
                            + "applied and served, but no events will reach Kafka. Set KAFKA_BOOTSTRAP_SERVERS "
                            + "(and KAFKA_SCHEMA_REGISTRY_URL) to enable it.",
                    LOSS_MARKER,
                    enabled ? "no broker is configured" : "app.kafka.enabled=false");
        }
    }

    /**
     * Hands the event to the publishing pool and returns immediately. Never throws: a messaging
     * problem must not fail — or slow down — a write that has already committed.
     */
    public void publish(FeatureChangeEvent event) {
        String key = partitionKey(event);
        if (!publishingEnabled) {
            recordLoss(event, key, "no Kafka broker is configured for this deployment", null);
            return;
        }
        try {
            executor.execute(() -> sendAndReport(event, key));
        } catch (RejectedExecutionException rejected) {
            // The queue is full, i.e. Kafka has been unreachable long enough to back the pool up.
            // Dropping here (AbortPolicy, not CallerRunsPolicy) is deliberate: running the send on
            // the request thread is the exact pool-exhaustion failure this class exists to prevent.
            recordLoss(event, key, "publish queue is full — the broker is likely unreachable", rejected);
        } catch (RuntimeException unexpected) {
            recordLoss(event, key, "could not be queued for publishing", unexpected);
        }
    }

    /** {@code tenantId:featureKey:envName} — see docs/feature-events.md, "Partition key & ordering". */
    static String partitionKey(FeatureChangeEvent event) {
        return event.getTenantId() + ":" + event.getFeatureKey() + ":" + event.getEnvName();
    }

    /** Number of events this process failed to hand to Kafka since startup. */
    public long getDroppedEventCount() {
        return droppedEvents.get();
    }

    private void sendAndReport(FeatureChangeEvent event, String key) {
        try {
            kafka.send(topic, key, event).whenComplete((result, ex) -> {
                if (ex != null) {
                    recordLoss(event, key, "broker did not acknowledge the record", ex);
                } else if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Published feature change event {} ({}) key={} topic={}",
                            event.getEventId(), event.getChangeType(), key, topic);
                }
            });
        } catch (Exception synchronousFailure) {
            // ConfigException / TimeoutException / SerializationException land here: send() threw
            // before it ever produced a future, so whenComplete above was never attached.
            recordLoss(event, key, "send failed before the record reached the producer", synchronousFailure);
        }
    }

    private void recordLoss(FeatureChangeEvent event, String key, String reason, Throwable cause) {
        long dropped = droppedEvents.incrementAndGet();
        LOGGER.error("{}: {} — event is LOST, the database change is already committed. "
                        + "eventId={} changeType={} tenantId={} featureKey={} envName={} "
                        + "actorUserId={} occurredAt={} topic={} partitionKey={} droppedSinceStartup={}",
                LOSS_MARKER, reason,
                event.getEventId(), event.getChangeType(), event.getTenantId(), event.getFeatureKey(),
                event.getEnvName(), event.getActorUserId(), event.getOccurredAt(), topic, key, dropped,
                cause);
    }
}
