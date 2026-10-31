package com.coursetrace.learning;

import com.coursetrace.processing.*;
import com.coursetrace.outbox.*;
import com.coursetrace.video.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

@Service
public class LearningWorker {
    private final ProcessingMapper tasks;
    private final TaskStepMapper steps;
    private final OutboxMapper outbox;
    private final CourseVideoMapper videos;
    private final MediaStorage storage;
    private final AudioExtractor extractor;
    private final ArtifactStore artifacts;
    private final AsrService asr;
    private final TranscriptService transcripts;
    private final NoteService notes;
    private final LearningWorkflow workflow;
    private final LearningJson json;
    private final Clock clock;
    private final TransactionTemplate tx;
    public LearningWorker(ProcessingMapper tasks,TaskStepMapper steps,OutboxMapper outbox,CourseVideoMapper videos,
        MediaStorage storage,AudioExtractor extractor,ArtifactStore artifacts,AsrService asr,TranscriptService transcripts,
        NoteService notes,LearningWorkflow workflow,LearningJson json,Clock clock,PlatformTransactionManager manager){
        this.tasks=tasks;this.steps=steps;this.outbox=outbox;this.videos=videos;this.storage=storage;this.extractor=extractor;
        this.artifacts=artifacts;this.asr=asr;this.transcripts=transcripts;this.notes=notes;this.workflow=workflow;this.json=json;this.clock=clock;this.tx=new TransactionTemplate(manager);
    }
    public void process(ProcessingEvent event){
        var context=tx.execute(status->claim(event));if(context==null)return;
        Result work=null;
        try{
            if(context.preFailure!=null)throw context.preFailure;
            Result result;
            try{result=execute(context);}catch(AudioExtractor.Deferred deferred){result=new Result(Map::of,null,0,5,true,false,true);}
            work=result;complete(context,result);
        }catch(LearningFailure failure){
            tx.executeWithoutResult(status->{
                var task=tasks.lock(context.task.id);if(task==null||task.runNo!=context.task.runNo)return;
                var step=steps.selectById(context.step.id);
                if(!"RUNNING".equals(step.status)||!context.step.executionToken.equals(step.executionToken))return;
                step.status="RETRY_WAIT";step.failureCount++;step.errorCode=failure.code;step.errorMessage=failure.getMessage();step.updatedAt=now();steps.saveLearning(step);
                task.status="RETRY_WAIT";task.errorCode=step.errorCode;task.errorMessage=step.errorMessage;task.updatedAt=now();tasks.save(task);
            });
            if(failure.retryable)throw new ProcessingFailures.Retryable();
            throw new ProcessingFailures.Permanent();
        } finally {if(work!=null)for(var candidate:work.candidates)artifacts.discardUnreferenced(candidate);}
    }
    private Context claim(ProcessingEvent event){
        var task=tasks.lock(event.taskId());verify(event);
        if(task==null||task.pipelineVersion!=2||task.runNo!=event.runNo()||"SUCCEEDED".equals(task.status)||"FAILED".equals(task.status))return null;
        var step=steps.current(task.id,task.runNo).stream().filter(s->s.stage.equals(event.stage())&&s.partId==event.partId()).findFirst().orElseThrow(ProcessingFailures.InvalidEvent::new);
        if(step.dispatchSeq!=event.dispatchSeq()||!task.currentStage.equals(step.stage)||List.of("RUNNING","SUCCEEDED","FAILED").contains(step.status))return null;
        var config=json.config(task.parametersJson);LearningFailure pre=null;
        boolean poll="ASR_POLL".equals(step.stage);
        if(step.deadlineAt!=null&&step.deadlineAt.isBefore(now()))pre=LearningFailure.permanent(poll?"ASR_WAIT_TIMEOUT":"STAGE_TIMEOUT",poll?"云端转录等待超时，可继续查询已有任务":"当前阶段已超时，请手动恢复");
        if(poll && step.pollCount>=config.maxPolls())pre=LearningFailure.permanent("ASR_POLL_BUDGET","云端转录查询次数已达上限，可手动继续查询");
        if((poll?step.failureCount:step.attemptCount)>=3)pre=LearningFailure.permanent("STAGE_RETRY_BUDGET","当前阶段自动重试已结束");
        if(pre==null){if(poll)step.pollCount++;else step.attemptCount++;}
        step.status="RUNNING";step.executionToken=UUID.randomUUID().toString();step.startedAt=now();step.updatedAt=now();step.errorCode=null;step.errorMessage=null;steps.saveLearning(step);
        task.status="RUNNING";task.startedAt=step.startedAt;task.updatedAt=now();task.errorCode=null;task.errorMessage=null;tasks.save(task);
        return new Context(task,step,videos.selectById(task.videoId),config,pre);
    }
    private Result execute(Context c){
        return switch(c.step.stage){
            case "SOURCE_CHECK" -> {
                if(storage.size(c.video.getBucketName(),c.video.getObjectKey())!=c.video.getSizeBytes())throw LearningFailure.permanent("SOURCE_SIZE_MISMATCH","原视频大小与上传记录不一致");
                if(c.video.getAudioCodec()==null)throw LearningFailure.permanent("NO_AUDIO","视频没有音轨，无法生成字幕");
                yield next(()->Map.of("actualSizeBytes",c.video.getSizeBytes()),"AUDIO_EXTRACT",0);
            }
            case "AUDIO_EXTRACT" -> {
                var audio=extractor.extract(c.video);
                yield next(()->{var saved=artifacts.save(audio);return Map.of("artifactId",saved.id);},"ASR_SUBMIT",0,audio);
            }
            case "ASR_SUBMIT" -> {
                var job=asr.submit(c.task,audio(c),c.config);
                yield new Result(()->Map.of("jobId",job.id),"ASR_POLL",0,job.rawArtifactId==null?5:0,false,true,false);
            }
            case "ASR_POLL" -> {
                var audio=audio(c);var job=asr.latest(audio.id,c.config);
                if(job==null)throw LearningFailure.permanent("ASR_TASK_MISSING","找不到已提交的转录任务");
                var polled=asr.poll(job,c.config);
                if(polled.waiting())yield new Result(()->Map.of("pollCount",c.step.pollCount),null,0,Math.min(30,5*(1<<Math.min(c.step.pollCount,3))),true,true,false);
                yield next(()->{asr.complete(polled);return Map.of("jobId",job.id,"artifactId",polled.raw().id);},"TRANSCRIPT_BUILD",0,polled.raw());
            }
            case "TRANSCRIPT_BUILD" -> {
                var raw=transcript(c);
                var drafts=transcripts.prepare(artifacts.text(raw),audio(c),c.video,c.config);
                int windows=transcripts.windowCount(drafts,c.config);
                yield next(()->{transcripts.save(c.video.getId(),raw.id,drafts);return Map.of("transcriptArtifactId",raw.id,"segmentCount",drafts.size(),"windowCount",windows);},"NOTE_BUILD",1);
            }
            case "NOTE_BUILD" -> {
                var raw=transcript(c);var windows=transcripts.windows(raw.id,c.config);
                if(c.step.partId<1||c.step.partId>windows.size())throw LearningFailure.permanent("NOTE_WINDOW_INVALID","笔记窗口标识不正确");
                var prepared=notes.generate(c.task,raw.id,c.step.partId,windows.get(c.step.partId-1),c.config);
                yield next(()->{notes.save(prepared);return Map.of("windowNo",c.step.partId,"windowCount",windows.size(),"chapterCount",prepared.notes().size());},
                    c.step.partId<windows.size()?"NOTE_BUILD":null,c.step.partId+1,prepared.artifact());
            }
            default -> throw LearningFailure.permanent("STAGE_INVALID","不支持的处理阶段");
        };
    }
    private Artifact audio(Context c){
        return artifacts.byId(result(c,"AUDIO_EXTRACT",0).path("artifactId").asLong());
    }
    private Artifact transcript(Context c){
        return artifacts.byId(result(c,"ASR_POLL",0).path("artifactId").asLong());
    }
    private com.fasterxml.jackson.databind.JsonNode result(Context c,String stage,int part){
        return steps.current(c.task.id,c.task.runNo).stream().filter(s->s.stage.equals(stage)&&s.partId==part&&"SUCCEEDED".equals(s.status))
            .map(s->json.read(s.resultJson)).findFirst().orElseThrow(()->LearningFailure.permanent("STAGE_PREREQUISITE","前置处理阶段尚未完成"));
    }
    private void complete(Context c,Result result){
        tx.executeWithoutResult(status->{
            var task=tasks.lock(c.task.id);if(task==null||task.runNo!=c.task.runNo)return;
            var step=steps.selectById(c.step.id);
            if(!"RUNNING".equals(step.status)||!c.step.executionToken.equals(step.executionToken))return;
            var data=result.save.get();step.updatedAt=now();
            if(result.repeat){
                if(result.deferred){step.attemptCount--;step.pollCount++;if(step.pollCount>120)throw LearningFailure.permanent("MEDIA_BUSY_TIMEOUT","等待媒体处理资源超时");}
                step.dispatchSeq++;step.status=result.external?"WAITING_EXTERNAL":"PENDING";step.resultJson=json.write(data);
                step.errorCode=null;step.errorMessage=null;steps.saveLearning(step);
                task.status=step.status;task.updatedAt=now();tasks.save(task);workflow.dispatch(task,step,result.delay);return;
            }
            step.status="SUCCEEDED";step.resultJson=json.write(data);step.finishedAt=now();steps.saveLearning(step);
            if(result.next!=null){workflow.pending(task,result.next,result.part,result.delay);}
            else{task.status="SUCCEEDED";task.finishedAt=now();task.updatedAt=now();task.errorCode=null;task.errorMessage=null;tasks.save(task);}
        });
    }
    public void deadLetter(ProcessingEvent event){
        tx.executeWithoutResult(status->{
            var task=tasks.lock(event.taskId());
            try{verify(event);}catch(ProcessingFailures.InvalidEvent invalid){return;}
            if(task==null||task.runNo!=event.runNo()||List.of("SUCCEEDED","RUNNING","FAILED").contains(task.status))return;
            var found=steps.current(task.id,task.runNo).stream().filter(s->s.stage.equals(event.stage())&&s.partId==event.partId()).findFirst();
            if(found.isEmpty())return;var step=found.get();
            if(step.dispatchSeq!=event.dispatchSeq()||!"RETRY_WAIT".equals(step.status))return;
            step.status="FAILED";step.finishedAt=now();step.updatedAt=now();steps.saveLearning(step);
            task.status="FAILED";task.errorCode=step.errorCode;task.errorMessage=step.errorMessage;task.finishedAt=now();task.updatedAt=now();tasks.save(task);
        });
    }
    private void verify(ProcessingEvent event){
        var e=outbox.selectById(event.eventId());
        if(e==null||e.taskId!=event.taskId()||e.pipelineVersion!=2||e.runNo!=event.runNo()||e.partId!=event.partId()
            ||!e.stage.equals(event.stage())||e.dispatchSeq!=event.dispatchSeq())throw new ProcessingFailures.InvalidEvent();
    }
    private Result next(Supplier<Map<String,Object>> save,String next,int part){return new Result(save,next,part,0,false,false,false);}
    private Result next(Supplier<Map<String,Object>> save,String next,int part,Artifact candidate){return new Result(save,next,part,0,false,false,false,candidate.id==null?List.of(candidate):List.of());}
    private LocalDateTime now(){return LocalDateTime.now(clock);}
    private record Context(ProcessingTask task,TaskStep step,CourseVideo video,LearningConfig config,LearningFailure preFailure){}
    private record Result(Supplier<Map<String,Object>> save,String next,int part,int delay,boolean repeat,boolean external,boolean deferred,List<Artifact> candidates){
        Result(Supplier<Map<String,Object>> save,String next,int part,int delay,boolean repeat,boolean external,boolean deferred){this(save,next,part,delay,repeat,external,deferred,List.of());}
    }
}
