package com.coursetrace.processing;

import com.coursetrace.common.ApiException;
import com.coursetrace.course.CourseService;
import com.coursetrace.outbox.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProcessingService {
    private final com.coursetrace.learning.LearningWorkflow learning;
    private final com.coursetrace.retrieval.IndexWorkflow indexing;
    private final com.coursetrace.learning.AsrService asr;
    private final ProcessingMapper tasks;
    private final TaskStepMapper steps;
    private final OutboxMapper outbox;
    private final CourseService courses;
    private final ObjectMapper json;
    private final ProcessingProperties properties;
    private final Clock clock;
    private final com.coursetrace.limit.RedisAdmission admission;
    private final TransactionTemplate tx;

    public ProcessingService(ProcessingMapper tasks, TaskStepMapper steps, OutboxMapper outbox, CourseService courses,
                             ObjectMapper json, ProcessingProperties properties, Clock clock, PlatformTransactionManager manager,com.coursetrace.learning.LearningWorkflow learning,com.coursetrace.learning.AsrService asr,com.coursetrace.retrieval.IndexWorkflow indexing,com.coursetrace.limit.RedisAdmission admission) {
        this.admission=admission;
        this.indexing=indexing; this.learning=learning; this.asr=asr; this.tasks=tasks; this.steps=steps; this.outbox=outbox; this.courses=courses;
        this.json=json; this.properties=properties; this.clock=clock; this.tx=new TransactionTemplate(manager);
    }

    public Submission submit(long ownerId,long videoId){return submit(ownerId,videoId,1);}
    public Submission submit(long ownerId, long videoId,int version) {
        if(version==3)throw new ApiException(HttpStatus.BAD_REQUEST,"USE_SEARCH_INDEX","请使用建立问答索引入口");
        version(version);
        return tx.execute(transaction -> {
            // Serializes submissions for this video; the unique index remains a second line of defense.
            var video=tasks.lockOwnedVideo(ownerId,videoId); if(video==null)throw notFound();
            var existing=tasks.findVersion(videoId,version);
            if (existing!=null) return new Submission(false, view(existing, steps.current(existing.id,existing.runNo)));
            if(version==2){learning.validate(video);admission.admit(ownerId,"learning","video:"+videoId,"initial");}
            var now=now();
            var task=new ProcessingTask();
            task.videoId=videoId; task.pipelineVersion=version; task.runNo=1;
            if(version==2)task.parametersJson=learning.snapshot();
            task.currentStage=ProcessingEvent.STAGE; task.status="PENDING"; task.createdAt=now; task.updatedAt=now;
            tasks.insert(task);
            if(task.pipelineVersion==2)learning.startRun(task);else if(task.pipelineVersion==3)indexing.startRun(task);else createRun(task);
            return new Submission(true, view(task, steps.current(task.id,task.runNo)));
        });
    }

    @Transactional(readOnly=true)
    public ProcessingTaskView get(long ownerId,long taskId) {
        var task=tasks.owned(ownerId,taskId);
        if (task==null) throw notFound();
        return view(task,steps.current(task.id,task.runNo));
    }

    @Transactional(readOnly=true)
    public List<ProcessingTaskView> batch(long ownerId,long courseId,List<Long> videoIds){return batch(ownerId,courseId,videoIds,1);}
    @Transactional(readOnly=true)
    public List<ProcessingTaskView> batch(long ownerId,long courseId,List<Long> videoIds,int version) {
        version(version);
        courses.get(ownerId,courseId);
        if (videoIds.size()>50 || videoIds.stream().anyMatch(id -> id==null || id<1))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_VIDEO_IDS","每次最多查询 50 个有效视频");
        var ids=videoIds.stream().distinct().toList();
        if (ids.isEmpty()) return List.of();
        if (tasks.countVideos(courseId,ids)!=ids.size()) throw notFound();
        var found=tasks.batchVersion(ownerId,courseId,ids,version);
        if (found.isEmpty()) return List.of();
        var byTask=steps.batch(found.stream().map(t -> t.id).toList()).stream().collect(Collectors.groupingBy(s -> s.taskId));
        return found.stream().map(t -> view(t,byTask.getOrDefault(t.id,List.of()))).toList();
    }

    public ProcessingTaskView retry(long ownerId,long taskId,int expectedRunNo){return retry(ownerId,taskId,expectedRunNo,false);}
    public ProcessingTaskView retry(long ownerId,long taskId,int expectedRunNo,boolean confirmResubmitUncertain) {
        return tx.execute(transaction -> {
            if (tasks.owned(ownerId,taskId)==null) throw notFound();
            var task=tasks.lock(taskId);
            if (task.runNo!=expectedRunNo)
                throw new ApiException(HttpStatus.CONFLICT,"TASK_CHANGED","任务已变化，请刷新状态后重试");
            if (!canRetry(task) && !(confirmResubmitUncertain && canConfirm(task)))
                throw new ApiException(HttpStatus.CONFLICT,"TASK_NOT_RETRYABLE","当前任务暂时不能重试，请刷新状态");
            if(task.pipelineVersion>1)admission.admit(ownerId,task.pipelineVersion==2?"learning":"index","recover:"+task.id+":"+(task.runNo+1),""+confirmResubmitUncertain);
            task.confirmResubmitUncertain=confirmResubmitUncertain;
            task.runNo++; task.status="PENDING"; task.errorCode=null; task.errorMessage=null;
            task.startedAt=null; task.finishedAt=null; task.updatedAt=now();
            tasks.save(task);
            outbox.cancelOld(task.id,task.runNo);
            if(task.pipelineVersion==2)learning.startRun(task);else if(task.pipelineVersion==3)indexing.startRun(task);else createRun(task);
            return view(task,steps.current(task.id,task.runNo));
        });
    }

    private void createRun(ProcessingTask task) {
        var step=new TaskStep();
        step.taskId=task.id; step.runNo=task.runNo; step.stage=ProcessingEvent.STAGE; step.partId=0;
        step.status="PENDING"; step.attemptCount=0; step.maxAttempts=3; step.createdAt=now(); step.updatedAt=step.createdAt;
        steps.insert(step);
        var event=new OutboxEvent();
        event.eventId=UUID.randomUUID().toString(); event.taskId=task.id; event.runNo=task.runNo;
        event.pipelineVersion=task.pipelineVersion; event.stage=step.stage; event.partId=0;
        event.topic=ProcessingEvent.TOPIC; event.messageKey=task.id.toString(); event.status="PENDING";
        event.publishAttempts=0; event.createdAt=now(); event.nextAttemptAt=event.createdAt;
        try { event.payloadJson=json.writeValueAsString(new ProcessingEvent(1,event.eventId,task.id,1,task.runNo,step.stage,0)); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("Could not encode processing event",failure); }
        outbox.insert(event);
    }

    private boolean canRetry(ProcessingTask task) {
        if(task.pipelineVersion==3 && java.util.Set.of("EMBEDDING_BUDGET","TRANSCRIPT_CHANGED","INVALID_EMBEDDING","CHUNK_CHECKPOINT_INVALID").contains(task.errorCode==null?"":task.errorCode))return false;
        if(task.pipelineVersion==2 && java.util.Set.of("ASR_SUBMISSION_UNKNOWN","ASR_SUBMISSION_BUDGET","NOTE_BUDGET","INVALID_NOTE","NO_SPEECH","TRANSCRIPT_TOO_LARGE").contains(task.errorCode==null?"":task.errorCode))return false;
        if(task.pipelineVersion==2 && "RUNNING".equals(task.status) && stale(task) && asr.uncertain(task.videoId))return false;
        return "FAILED".equals(task.status) || ("RUNNING".equals(task.status) && task.startedAt!=null
                && !task.startedAt.plusMinutes(properties.getStaleMinutes()).isAfter(now()));
    }

    private ProcessingTaskView view(ProcessingTask task,List<TaskStep> current) {
        var views=current.stream().map(step -> {
            com.fasterxml.jackson.databind.JsonNode result=null;
            if (step.resultJson!=null) {
                try { result=json.readTree(step.resultJson); }
                catch (JsonProcessingException failure) { throw new IllegalStateException("Invalid persisted task result",failure); }
            }
            return new ProcessingTaskView.StepView(step.stage,step.partId,step.status,step.attemptCount,step.maxAttempts,
                    result,instant(step.startedAt),instant(step.finishedAt),step.pollCount,step.failureCount);
        }).toList();
        return new ProcessingTaskView(task.id,task.videoId,task.pipelineVersion,task.runNo,task.currentStage,task.status,
                task.errorCode,task.errorMessage,instant(task.createdAt),instant(task.updatedAt),
                instant(task.startedAt),instant(task.finishedAt),canRetry(task),views,canConfirm(task));
    }

    private Instant instant(LocalDateTime time) { return time==null ? null : time.toInstant(ZoneOffset.UTC); }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND,"TASK_NOT_FOUND","视频或任务不存在或无法访问"); }
    public record Submission(boolean created,ProcessingTaskView task) {}
    private boolean stale(ProcessingTask task){return task.startedAt!=null&&!task.startedAt.plusMinutes(properties.getStaleMinutes()).isAfter(now());}
    private boolean canConfirm(ProcessingTask task){
        return task.pipelineVersion==2 && ("FAILED".equals(task.status)||("RUNNING".equals(task.status)&&stale(task)))
            && ("ASR_SUBMISSION_UNKNOWN".equals(task.errorCode)||stale(task)) && asr.canResubmit(task.videoId);
    }
    private void version(int version){if(version!=1&&version!=2&&version!=3)throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PIPELINE","不支持的处理流程");}
}
