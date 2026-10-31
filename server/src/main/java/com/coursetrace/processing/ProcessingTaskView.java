package com.coursetrace.processing;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

public record ProcessingTaskView(long id,long videoId,int pipelineVersion,int runNo,String stage,String status,
    String errorCode,String errorMessage,Instant createdAt,Instant updatedAt,Instant startedAt,Instant finishedAt,
    boolean canRetry,List<StepView> steps,boolean canResubmitUncertain) {
    public record StepView(String stage,int partId,String status,int attemptCount,int maxAttempts,
        JsonNode result,Instant startedAt,Instant finishedAt,int pollCount,int failureCount) {}
}
