package com.coursetrace.processing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.*;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableKafka
@EnableScheduling
@ConditionalOnProperty(prefix="coursetrace.processing",name="enabled",havingValue="true",matchIfMissing=true)
public class ProcessingKafkaConfiguration extends RetryTopicConfigurationSupport {
    @Override
    protected void configureCustomizers(CustomizersConfigurer customizers) {
        customizers.customizeDeadLetterPublishingRecoverer(recoverer -> {
            recoverer.setFailIfSendResultIsError(true);
            recoverer.setWaitForSendResultTimeout(java.time.Duration.ofSeconds(12));
        });
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<Object,Object> processingDltContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,ConsumerFactory<Object,Object> consumers) {
        var factory=new ConcurrentKafkaListenerContainerFactory<Object,Object>();
        configurer.configure(factory,consumers);
        // A database failure in the DLT handler stops this container without acknowledging the failed record.
        // Restart after fixing the database. Do not circulate an unpersisted failure endlessly through DLT.
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }
}
