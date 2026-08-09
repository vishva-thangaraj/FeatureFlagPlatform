package com.feature_flag.platform.feature.messaging;

import com.feature_flag.platform.feature.events.FeatureChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;


@Component
public class FeatureEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(FeatureEventPublisher.class);
    private final KafkaTemplate<String, FeatureChangeEvent> kafka;
    private final String topic;

    public FeatureEventPublisher(KafkaTemplate<String, FeatureChangeEvent> kafka,@Value("${app.kafka.topic.feature-changes}") String topic) {
        this.kafka = kafka;
        this.topic = topic;
    }

    public void publish(FeatureChangeEvent event)
    {
        String key = event.getTenantId() + ":" + event.getFeatureKey() + ":" + event.getEnvName();

        kafka.send(topic, key, event).whenComplete((res, ex) ->{
            if(ex != null) {
                LOGGER.error("Failed to publish feature change event: {} : {}",event.getChangeType(), key, ex);
            }
        });
    }
}
