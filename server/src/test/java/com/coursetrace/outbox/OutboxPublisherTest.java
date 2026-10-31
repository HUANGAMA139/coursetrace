package com.coursetrace.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class OutboxPublisherTest {
    @SuppressWarnings("unchecked")
    @Test void brokerFailureDoesNotMarkEventSent() {
        var mapper=mock(OutboxMapper.class); var service=mock(OutboxService.class);
        var kafka=(KafkaTemplate<Object,Object>)mock(KafkaTemplate.class);
        var event=event();
        when(mapper.due(any())).thenReturn(List.of(event));
        when(kafka.send(anyString(),any(),any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("injected broker failure")));
        new OutboxPublisher(mapper,service,kafka,Clock.systemUTC()).publishDue();
        verify(service).publishFailed(event); verify(mapper,never()).sent(anyString(),any());
    }

    @SuppressWarnings("unchecked")
    @Test void ackMarkerDatabaseFailureLeavesEventForRedeliveryInsteadOfBusinessFailure() {
        var mapper=mock(OutboxMapper.class); var service=mock(OutboxService.class);
        var kafka=(KafkaTemplate<Object,Object>)mock(KafkaTemplate.class);
        var event=event();
        when(mapper.due(any())).thenReturn(List.of(event));
        when(kafka.send(anyString(),any(),any())).thenReturn(CompletableFuture.completedFuture(null));
        when(mapper.sent(anyString(),any())).thenThrow(new IllegalStateException("injected database failure"));
        assertThatCode(()->new OutboxPublisher(mapper,service,kafka,Clock.systemUTC()).publishDue()).doesNotThrowAnyException();
        assertThat(event.status).isEqualTo("PENDING");
        verifyNoInteractions(service);
    }

    private OutboxEvent event() {
        var event=new OutboxEvent(); event.eventId="test-event"; event.topic="course.processing";
        event.messageKey="1"; event.payloadJson="{}"; event.status="PENDING"; return event;
    }
}
