package com.coursetrace.outbox;

import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix="coursetrace.processing",name={"enabled","publisher-enabled"},havingValue="true",matchIfMissing=true)
public class OutboxPublisher {
    private static final Logger log=LoggerFactory.getLogger(OutboxPublisher.class);
    private final OutboxMapper outbox;
    private final OutboxService service;
    private final KafkaTemplate<Object,Object> kafka;
    private final Clock clock;
    public OutboxPublisher(OutboxMapper outbox,OutboxService service,KafkaTemplate<Object,Object> kafka,Clock clock) {
        this.outbox=outbox; this.service=service; this.kafka=kafka; this.clock=clock;
    }

    @Scheduled(fixedDelayString="${coursetrace.processing.publish-delay-ms:1000}")
    public void publishDue() {
        for (var event:outbox.due(LocalDateTime.now(clock))) {
            try { kafka.send(event.topic,event.messageKey,event.payloadJson).get(12,TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            catch (Exception failure) {
                service.publishFailed(event);
                log.warn("Outbox publish deferred: event={}, reason={}",event.eventId,failure.getClass().getSimpleName());
                continue;
            }
            // An ACK followed by a database failure must leave PENDING for safe redelivery.
            try { outbox.sent(event.eventId,LocalDateTime.now(clock)); }
            catch (RuntimeException failure) { log.warn("Outbox ACK marker pending: event={}, reason={}",event.eventId,failure.getClass().getSimpleName()); }
        }
    }
}
