package com.coursetrace.processing;

import com.coursetrace.common.ApiException;
import com.coursetrace.outbox.OutboxMapper;
import com.coursetrace.storage.ObjectStorage;
import com.coursetrace.video.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.Map;
import java.util.UUID;

@Service
public class ProcessingWorker {
    private static final Logger log=LoggerFactory.getLogger(ProcessingWorker.class);
    private final com.coursetrace.learning.LearningWorker learning;
    private final com.coursetrace.retrieval.IndexWorker indexing;
    private final ProcessingMapper tasks;
    private final TaskStepMapper steps;
    private final OutboxMapper outbox;
    private final CourseVideoMapper videos;
    private final ObjectStorage storage;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ProcessingWorker(ProcessingMapper tasks,TaskStepMapper steps,OutboxMapper outbox,CourseVideoMapper videos,
                            ObjectStorage storage,ObjectMapper json,Clock clock,PlatformTransactionManager manager,com.coursetrace.learning.LearningWorker learning,com.coursetrace.retrieval.IndexWorker indexing) {
        this.indexing=indexing; this.learning=learning; this.tasks=tasks; this.steps=steps; this.outbox=outbox; this.videos=videos; this.storage=storage;
        this.json=json; this.clock=clock; this.tx=new TransactionTemplate(manager);
    }

    public void process(String payload) {
        var event=parse(payload);
        if(event.pipelineVersion()==2){learning.process(event);return;}
        if(event.pipelineVersion()==3){indexing.process(event);return;}
        var claim=tx.execute(transaction -> {
            var task=tasks.lock(event.taskId());
            verifyBoundEvent(event);
            if (task==null || task.runNo!=event.runNo() || task.pipelineVersion!=event.pipelineVersion()
                    || "SUCCEEDED".equals(task.status) || "FAILED".equals(task.status)) return null;
            var step=steps.current(task.id,task.runNo).stream().filter(s -> s.stage.equals(event.stage()) && s.partId==event.partId()).findFirst().orElseThrow(ProcessingFailures.InvalidEvent::new);
            if ("RUNNING".equals(step.status) || "SUCCEEDED".equals(step.status) || "FAILED".equals(step.status)) return null;
            if (step.attemptCount>=step.maxAttempts) throw new ProcessingFailures.Permanent();
            var token=UUID.randomUUID().toString();
            if (steps.claim(step.id,token,now())!=1) return null;
            task.status="RUNNING"; task.startedAt=now(); task.updatedAt=task.startedAt;
            task.finishedAt=null; task.errorCode=null; task.errorMessage=null; tasks.save(task);
            return new Claim(task.id,task.runNo,step.id,token,videos.selectById(task.videoId));
        });
        if (claim==null) return;

        long actualSize;
        try { actualSize=storage.size(claim.video.getBucketName(),claim.video.getObjectKey()); }
        catch (ApiException failure) {
            boolean retryable=failure.getStatus().is5xxServerError();
            finish(claim,"RETRY_WAIT",null,retryable?"STORAGE_UNAVAILABLE":"SOURCE_MISSING",
                    retryable?"视频存储暂时不可用，正在尝试恢复":"视频文件不存在，请检查原文件或重新上传");
            if (retryable) throw new ProcessingFailures.Retryable();
            throw new ProcessingFailures.Permanent();
        }
        if (actualSize!=claim.video.getSizeBytes()) {
            finish(claim,"RETRY_WAIT",null,"SOURCE_SIZE_MISMATCH","视频文件大小与上传记录不符，请检查原文件或重新上传");
            throw new ProcessingFailures.Permanent();
        }
        String result;
        try { result=json.writeValueAsString(Map.of("actualSizeBytes",actualSize,"checkedAt",clock.instant().toString())); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Could not encode source check result",failure); }
        finish(claim,"SUCCEEDED",result,null,null);
    }

    private void finish(Claim claim,String status,String result,String errorCode,String errorMessage) {
        tx.executeWithoutResult(transaction -> {
            // Lock the task before its step in every path, including manual retry and DLT.
            var task=tasks.lock(claim.taskId);
            if (task==null || task.runNo!=claim.runNo) return;
            var step=steps.selectById(claim.stepId);
            if (!"RUNNING".equals(step.status) || !claim.token.equals(step.executionToken)) return;
            step.status=status; step.resultJson=result; step.errorCode=errorCode; step.errorMessage=errorMessage;
            step.updatedAt=now(); step.finishedAt="SUCCEEDED".equals(status)?step.updatedAt:null;
            if (steps.finish(step)!=1) throw new IllegalStateException("Execution changed while saving result");
            task.status=status; task.errorCode=errorCode; task.errorMessage=errorMessage; task.updatedAt=step.updatedAt;
            task.finishedAt=step.finishedAt; tasks.save(task);
        });
    }

    public void deadLetter(String payload) {
        ProcessingEvent event;
        try { event=parse(payload); }
        catch (ProcessingFailures.InvalidEvent invalid) { log.warn("Quarantined malformed processing event"); return; }
        if(event.pipelineVersion()==2){learning.deadLetter(event);return;}
        if(event.pipelineVersion()==3){indexing.deadLetter(event);return;}
        tx.executeWithoutResult(transaction -> {
            var task=tasks.lock(event.taskId());
            try { verifyBoundEvent(event); }
            catch (ProcessingFailures.InvalidEvent invalid) { log.warn("Quarantined unbound processing event"); return; }
            if (task==null || task.runNo!=event.runNo() || "SUCCEEDED".equals(task.status)
                    || "RUNNING".equals(task.status) || "FAILED".equals(task.status)) return;
            var current=steps.current(task.id,task.runNo);
            if (current.isEmpty()) return;
            var step=current.getFirst();
            if (!"RETRY_WAIT".equals(step.status)) return;
            step.errorCode=step.errorCode==null?"PROCESSING_FAILED":step.errorCode;
            step.errorMessage="STORAGE_UNAVAILABLE".equals(step.errorCode)
                    ?"视频存储暂时不可用，自动重试已结束；恢复服务后可手动重试":step.errorMessage;
            step.updatedAt=now(); step.finishedAt=step.updatedAt;
            if (steps.failWaiting(step)!=1) return;
            task.status="FAILED"; task.errorCode=step.errorCode; task.errorMessage=step.errorMessage;
            task.updatedAt=step.updatedAt; task.finishedAt=step.finishedAt; tasks.save(task);
        });
    }

    private ProcessingEvent parse(String payload) {
        if (payload==null || payload.length()>4096) throw new ProcessingFailures.InvalidEvent();
        try {
            var event=json.readValue(payload,ProcessingEvent.class);
            if (event==null || !event.valid()) throw new ProcessingFailures.InvalidEvent();
            return event;
        } catch (JsonProcessingException failure) { throw new ProcessingFailures.InvalidEvent(); }
    }

    private void verifyBoundEvent(ProcessingEvent event) {
        var persisted=outbox.selectById(event.eventId());
        if (persisted==null || persisted.taskId!=event.taskId() || persisted.runNo!=event.runNo()
                || persisted.pipelineVersion!=event.pipelineVersion() || persisted.partId!=event.partId()
                || !persisted.stage.equals(event.stage())) throw new ProcessingFailures.InvalidEvent();
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private record Claim(long taskId,int runNo,long stepId,String token,CourseVideo video) {}
}
