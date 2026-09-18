package com.feature_flag.platform.feature.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class FeatureEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(FeatureEventListener.class);

    private final FeatureEventPublisher publisher;

    public FeatureEventListener(FeatureEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Publishes after the writing transaction commits, so no consumer can ever observe a change the
     * database rolled back.
     *
     * <p>{@code fallbackExecution = true} is deliberate. With the default ({@code false}) an event
     * raised outside a transaction is <em>silently discarded</em> with a single DEBUG line — a
     * refactor that moves one of the {@code FeatureService} write paths out of {@code @Transactional}
     * would stop emitting events and nothing would say so. Every current caller is transactional, so
     * this branch should never run; if it ever does, the event is still published and the anomaly is
     * logged at WARN rather than vanishing.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publishEvent(FeatureChanged featureChanged) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            LOGGER.warn("Feature change event raised outside a transaction — publishing it anyway, but the "
                            + "caller is not covered by AFTER_COMMIT and the database write may not be durable. "
                            + "eventId={} changeType={} tenantId={} featureKey={} envName={}",
                    featureChanged.event().getEventId(), featureChanged.event().getChangeType(),
                    featureChanged.event().getTenantId(), featureChanged.event().getFeatureKey(),
                    featureChanged.event().getEnvName());
        }
        publisher.publish(featureChanged.event());
    }
}
