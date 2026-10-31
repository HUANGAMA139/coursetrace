package com.coursetrace.processing;

import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport.CustomizersConfigurer;
import org.springframework.util.backoff.FixedBackOff;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class KafkaRecoveryTest {
    @SuppressWarnings("unchecked")
    @Test void failedRetryOrDltSendIsNotRecoveredAndDoesNotCommitSourceOffset() {
        var kafka=(KafkaTemplate<Object,Object>)mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new KafkaException("injected send failure")));
        var recoverer=new DeadLetterPublishingRecoverer(kafka,(record,failure)->new TopicPartition("course.processing.retry-2000",0));
        recoverer.setVerifyPartition(false);
        var customizers=mock(CustomizersConfigurer.class);
        doAnswer(call -> { ((Consumer<DeadLetterPublishingRecoverer>)call.getArgument(0)).accept(recoverer); return customizers; })
                .when(customizers).customizeDeadLetterPublishingRecoverer(any());
        new ProcessingKafkaConfiguration().configureCustomizers(customizers);
        var handler=new DefaultErrorHandler(recoverer,new FixedBackOff(0,0));
        var consumer=(org.apache.kafka.clients.consumer.Consumer<Object,Object>)mock(org.apache.kafka.clients.consumer.Consumer.class);
        when(consumer.groupMetadata()).thenReturn(new ConsumerGroupMetadata("test-recovery"));
        var container=mock(MessageListenerContainer.class);
        var properties=new ContainerProperties("course.processing"); properties.setAckMode(ContainerProperties.AckMode.RECORD);
        when(container.getContainerProperties()).thenReturn(properties);
        var record=new ConsumerRecord<Object,Object>("course.processing",0,17L,"1","{}");
        assertThatThrownBy(()->handler.handleRemaining(new ProcessingFailures.Retryable(),List.of(record),consumer,container))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("not yet recovered");
        verify(consumer).seek(new TopicPartition("course.processing",0),17L);
        verify(consumer,never()).commitSync(anyMap());
        verify(consumer,never()).commitAsync(anyMap(),any());
    }

    @SuppressWarnings("unchecked")
    @Test void dltDatabaseFailureStopsContainerAndLeavesFailedRecordUnacknowledged() {
        var configurer=mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        var consumers=(ConsumerFactory<Object,Object>)mock(ConsumerFactory.class);
        var factory=new ProcessingKafkaConfiguration().processingDltContainerFactory(configurer,consumers);
        factory.setConsumerFactory(consumers);
        var errorHandler=factory.createContainer("course.processing.dlt").getCommonErrorHandler();
        assertThat(errorHandler).isInstanceOf(CommonContainerStoppingErrorHandler.class);
        var consumer=(org.apache.kafka.clients.consumer.Consumer<Object,Object>)mock(org.apache.kafka.clients.consumer.Consumer.class);
        var container=mock(MessageListenerContainer.class);
        var record=new ConsumerRecord<Object,Object>("course.processing.dlt",0,9L,"1","{}");
        assertThatThrownBy(()->errorHandler.handleRemaining(new IllegalStateException("injected DLT database failure"),List.of(record),consumer,container))
                .isInstanceOf(KafkaException.class);
        verify(container,timeout(2000)).stopAbnormally(any(Runnable.class));
        verifyNoInteractions(consumer);
    }
}
