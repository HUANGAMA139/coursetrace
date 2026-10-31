package com.coursetrace.processing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.*;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="coursetrace.processing",name="enabled",havingValue="true",matchIfMissing=true)
public class ProcessingListener {
    private final ProcessingWorker worker;
    public ProcessingListener(ProcessingWorker worker) { this.worker=worker; }

    @RetryableTopic(attempts="3",backoff=@Backoff(delay=2000,multiplier=2),
            include=ProcessingFailures.Retryable.class,traversingCauses="true",kafkaTemplate="kafkaTemplate",
            retryTopicSuffix=".retry",dltTopicSuffix=".dlt",numPartitions="1",replicationFactor="1",
            topicSuffixingStrategy=TopicSuffixingStrategy.SUFFIX_WITH_DELAY_VALUE,
            concurrency="1",autoStartDltHandler="false")
    @KafkaListener(id="course-processing",topics=ProcessingEvent.TOPIC,groupId="coursetrace-processing",concurrency="1")
    public void process(String payload) { worker.process(payload); }

    // Use a dedicated, stopping error handler instead of the retry infrastructure's default DLT logging/re-forwarding.
    @KafkaListener(id="course-processing-dlt",topics=ProcessingEvent.TOPIC+".dlt",
            groupId="coursetrace-processing-dlt",containerFactory="processingDltContainerFactory",concurrency="1")
    public void deadLetter(String payload) { worker.deadLetter(payload); }
}
