package com.feature_flag.platform.feature.messaging;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class FeatureEventListener {
    private final FeatureEventPublisher publisher;

    public FeatureEventListener(FeatureEventPublisher publisher) {
        this.publisher = publisher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishEvent(FeatureChanged featureChanged)
    {
        publisher.publish(featureChanged.event());
    }
}
