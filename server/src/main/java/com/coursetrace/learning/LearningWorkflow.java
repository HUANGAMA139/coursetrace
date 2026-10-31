package com.coursetrace.learning;

import com.coursetrace.processing.*;
import com.coursetrace.outbox.*;
import com.coursetrace.video.CourseVideo;
import com.coursetrace.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.*;
import java.util.*;

@Service
public class LearningWorkflow {
    public static final List<String> PREFIX=List.of("SOURCE_CHECK","AUDIO_EXTRACT","ASR_SUBMIT","ASR_POLL","TRANSCRIPT_BUILD");
    private final TaskStepMapper steps;
    private final OutboxMapper outbox;
    private final ProcessingMapper tasks;
    private final ArtifactMapper artifacts;
    private final AsrJobMapper jobs;
    private final LearningProperties properties;
    private final QwenAsrClient client;
    private final LearningJson json;
    private final Clock clock;
    public LearningWorkflow(TaskStepMapper steps,OutboxMapper outbox,ProcessingMapper tasks,ArtifactMapper artifacts,
                            LearningProperties properties,QwenAsrClient client,LearningJson json,Clock clock,AsrJobMapper jobs){
        this.steps=steps;this.outbox=outbox;this.tasks=tasks;this.artifacts=artifacts;this.properties=properties;this.client=client;this.json=json;this.clock=clock;this.jobs=jobs;
    }
    public Map<String,Object> capabilities(){
        boolean tools=Files.isRegularFile(Path.of(properties.getFfmpegPath()));
        return Map.of("configured",client.configured()&&tools,"maxDurationSeconds",properties.getMaxDurationSeconds(),
            "message",!client.configured()?"转录与笔记服务尚未配置":!tools?"媒体处理工具尚未就绪":"可以生成字幕与笔记");
    }
    public void validate(CourseVideo video){
        if(!client.configured())throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"AI_NOT_CONFIGURED","转录与笔记服务尚未配置");
        if(!Files.isRegularFile(Path.of(properties.getFfmpegPath())))throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"MEDIA_UNAVAILABLE","媒体处理工具尚未就绪");
        if(video.getAudioCodec()==null)throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"NO_AUDIO","视频没有音轨，无法生成字幕");
        if(video.getDurationMs()>properties.getMaxDurationSeconds()*1000L)throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"LEARNING_TOO_LONG","视频超过本阶段处理时长上限");
    }
    public String snapshot(){return json.write(properties.snapshot());}
    public void startRun(ProcessingTask task){
        var config=json.config(task.parametersJson);
        var previous=task.runNo>1?steps.current(task.id,task.runNo-1):List.<TaskStep>of();
        int windows=1;
        for(String stage:PREFIX){
            var reusable=find(previous,stage,0);
            if(reusable==null){pending(task,stage,0,0);return;}
            cloneStep(task,reusable);
            if("TRANSCRIPT_BUILD".equals(stage))windows=json.read(reusable.resultJson).path("windowCount").asInt(1);
        }
        for(int part=1;part<=windows;part++){
            var reusable=find(previous,"NOTE_BUILD",part);
            if(reusable==null){pending(task,"NOTE_BUILD",part,0);return;}
            cloneStep(task,reusable);
        }
        task.currentStage="NOTE_BUILD";task.status="SUCCEEDED";task.finishedAt=now();task.updatedAt=now();tasks.save(task);
    }
    private TaskStep find(List<TaskStep> previous,String stage,int part){
        for(var s:previous)if(s.stage.equals(stage)&&s.partId==part&&"SUCCEEDED".equals(s.status)){
            if(("AUDIO_EXTRACT".equals(stage)||"ASR_POLL".equals(stage))&&s.resultJson!=null){
                long id=json.read(s.resultJson).path("artifactId").asLong();
                var artifact=artifacts.selectById(id);
                if(artifact==null||!"READY".equals(artifact.state))return null;
            }
            if("ASR_SUBMIT".equals(stage)){var job=jobs.selectById(json.read(s.resultJson).path("jobId").asLong());if(job==null||"FAILED".equals(job.status))return null;}
            return s;
        }
        return null;
    }
    private void cloneStep(ProcessingTask task,TaskStep original){
        var step=new TaskStep();step.taskId=task.id;step.runNo=task.runNo;step.stage=original.stage;step.partId=original.partId;
        step.status="SUCCEEDED";step.maxAttempts=3;step.createdAt=now();step.updatedAt=step.createdAt;step.startedAt=step.createdAt;step.finishedAt=step.createdAt;
        var result=json.read(original.resultJson);
        if(result.isObject())((com.fasterxml.jackson.databind.node.ObjectNode)result).put("reusedFromRun",original.runNo);
        step.resultJson=json.write(result);steps.insert(step);
    }
    // Call only in the transaction that saves the preceding stage and its products.
    public TaskStep pending(ProcessingTask task,String stage,int part,int delay){
        var step=new TaskStep();step.taskId=task.id;step.runNo=task.runNo;step.stage=stage;step.partId=part;
        step.status="ASR_POLL".equals(stage)?"WAITING_EXTERNAL":"PENDING";step.maxAttempts=3;
        step.createdAt=now();step.updatedAt=step.createdAt;
        step.deadlineAt=now().plusMinutes("ASR_POLL".equals(stage)?json.config(task.parametersJson).waitMinutes():10);
        steps.insert(step);
        task.currentStage=stage;task.status=step.status;task.updatedAt=now();task.startedAt=null;task.finishedAt=null;task.errorCode=null;task.errorMessage=null;tasks.save(task);
        dispatch(task,step,delay);return step;
    }
    public void dispatch(ProcessingTask task,TaskStep step,int delay){
        var event=new OutboxEvent();event.eventId=UUID.randomUUID().toString();event.taskId=task.id;event.runNo=task.runNo;
        event.pipelineVersion=2;event.stage=step.stage;event.partId=step.partId;event.dispatchSeq=step.dispatchSeq;
        event.topic=ProcessingEvent.TOPIC;event.messageKey=task.id.toString();event.status="PENDING";
        event.createdAt=now();event.nextAttemptAt=event.createdAt.plusSeconds(delay);
        event.payloadJson=json.write(new ProcessingEvent(2,event.eventId,task.id,2,task.runNo,step.stage,step.partId,step.dispatchSeq));outbox.insert(event);
    }
    private LocalDateTime now(){return LocalDateTime.now(clock);}
}
