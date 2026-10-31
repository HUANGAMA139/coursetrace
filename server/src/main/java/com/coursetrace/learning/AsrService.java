package com.coursetrace.learning;

import com.coursetrace.processing.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.Map;

@Service
public class AsrService {
    private final AsrJobMapper jobs;
    private final ArtifactStore store;
    private final MediaStorage storage;
    private final QwenAsrClient client;
    private final ProcessingMapper tasks;
    private final LearningJson json;
    private final Clock clock;
    private final TransactionTemplate tx;
    public AsrService(AsrJobMapper jobs,ArtifactStore store,MediaStorage storage,QwenAsrClient client,
                      ProcessingMapper tasks,LearningJson json,Clock clock,PlatformTransactionManager manager){
        this.jobs=jobs;this.store=store;this.storage=storage;this.client=client;this.tasks=tasks;this.json=json;this.clock=clock;this.tx=new TransactionTemplate(manager);
    }
    public static String configHash(LearningConfig c){return LearningJson.hash(c.asrModel()+":"+c.nativeBase()+":zh,en:mono:v1");}
    public AsrJob latest(long audioId,LearningConfig config){return jobs.latest(audioId,configHash(config));}
    public AsrJob submit(ProcessingTask task,Artifact audio,LearningConfig config){
        var job=tx.execute(status->{
            var locked=tasks.lock(task.id);
            if(locked.runNo!=task.runNo)throw LearningFailure.permanent("TASK_CHANGED","任务轮次已变化");
            var existing=latest(audio.id,config);
            if(existing!=null && existing.providerTaskId!=null && !"FAILED".equals(existing.status))return existing;
            if(existing!=null && ("SUBMITTING".equals(existing.status)||"SUBMISSION_UNKNOWN".equals(existing.status)) && !task.confirmResubmitUncertain)
                throw uncertain();
            if(existing!=null && ("PREPARING".equals(existing.status)||("FAILED".equals(existing.status)&&existing.providerTaskId==null))){existing.status="PREPARING";jobs.updateById(existing);return existing;}
            if(existing!=null && existing.submissionNo>=2)throw LearningFailure.permanent("ASR_SUBMISSION_BUDGET","转录提交预算已耗尽，请先核对已有任务");
            var created=new AsrJob();created.videoId=task.videoId;created.audioArtifactId=audio.id;created.configHash=configHash(config);
            created.runNo=task.runNo;created.submissionNo=existing==null?1:existing.submissionNo+1;created.status="PREPARING";
            created.createdAt=now();created.updatedAt=created.createdAt;jobs.insert(created);return created;
        });
        if(job.providerTaskId!=null)return job;
        Path temp=null;boolean posted=false;
        try{
            temp=Files.createTempFile("coursetrace-asr-",".flac");
            storage.download(audio.bucketName,audio.objectKey,temp,audio.sizeBytes);
            if(Files.size(temp)!=audio.sizeBytes || (audio.sha256!=null&&!ArtifactStore.fileHash(temp).equals(audio.sha256)))throw LearningFailure.permanent("ARTIFACT_CORRUPT","音频产物大小或摘要校验失败");
            String url=client.upload(temp,config);
            job.temporaryUrl=url;job.temporaryExpiresAt=now().plusHours(48);job.updatedAt=now();jobs.updateById(job);
            job.status="SUBMITTING";jobs.updateById(job); // Durable intent before the billable POST.
            try{
                posted=true;String remoteId=client.submit(url,config);
                job.providerTaskId=remoteId;job.status="PENDING";job.updatedAt=now();
                // This identity belongs to the remote submission, even if the UI run has changed.
                jobs.accepted(job);return jobs.selectById(job.id);
            }catch(LearningFailure failure){
                job.status=failure.uncertain?"SUBMISSION_UNKNOWN":failure.retryable?"PREPARING":"FAILED";
                job.errorCode=failure.code;job.updatedAt=now();jobs.updateById(job);throw failure;
            }
        }catch(LearningFailure e){throw e;}
        catch(Exception e){throw new LearningFailure(posted?"ASR_SUBMISSION_UNKNOWN":"ASR_PREPARE_FAILED",posted?"转录提交结果待确认，暂不自动重新提交":"无法准备转录请求，请检查存储与本地目录",!posted,posted);}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(Exception ignored){}}
    }
    public PollResult poll(AsrJob job,LearningConfig config){
        if(job.rawArtifactId!=null && "SUCCEEDED".equals(job.status))return new PollResult(job,store.byId(job.rawArtifactId),false);
        if(job.providerTaskId==null)throw uncertain();
        var response=client.poll(job.providerTaskId,config).path("output");
        String status=response.path("task_status").asText();
        if("PENDING".equals(status)||"RUNNING".equals(status)){
            job.status=status;job.pollCount++;job.updatedAt=now();jobs.updateById(job);
            return new PollResult(job,null,true);
        }
        if(!"SUCCEEDED".equals(status)){
            job.status="FAILED";job.errorCode="ASR_REMOTE_FAILED";job.updatedAt=now();jobs.updateById(job);
            throw LearningFailure.permanent("ASR_REMOTE_FAILED","云端转录失败，请检查音频或稍后重试");
        }
        var results=response.path("results");
        if(results.size()!=1 || !"SUCCEEDED".equals(results.get(0).path("subtask_status").asText("SUCCEEDED")) || results.get(0).path("transcription_url").asText().isBlank())
        { job.status="FAILED";job.errorCode="ASR_FILE_FAILED";job.updatedAt=now();jobs.updateById(job);
            throw LearningFailure.permanent("ASR_FILE_FAILED","音频文件转录失败，未取得有效结果"); }
        String body=client.result(results.get(0).path("transcription_url").asText());
        json.read(body);
        String fingerprint=LearningJson.hash(job.id+":"+job.providerTaskId);
        var raw=store.ready(job.videoId,"ASR_JSON",fingerprint);
        if(raw==null)raw=store.prepareJson(job.videoId,"ASR_JSON",fingerprint,body,json.write(Map.of("asrModel",config.asrModel())));
        return new PollResult(job,raw,false);
    }
    public void complete(PollResult result){
        var artifact=store.save(result.raw);var job=result.job;
        job.rawArtifactId=artifact.id;job.status="SUCCEEDED";job.pollCount++;job.updatedAt=now();jobs.updateById(job);
    }
    public boolean uncertain(long videoId){
        var list=jobs.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<AsrJob>query().eq("video_id",videoId).orderByDesc("id").last("LIMIT 1"));
        return !list.isEmpty() && list.getFirst().providerTaskId==null
            && ("SUBMISSION_UNKNOWN".equals(list.getFirst().status)||"SUBMITTING".equals(list.getFirst().status));
    }
    private LearningFailure uncertain(){return new LearningFailure("ASR_SUBMISSION_UNKNOWN","转录提交结果待确认；重新提交可能产生重复费用",false,true);}
    private LocalDateTime now(){return LocalDateTime.now(clock);}
    public record PollResult(AsrJob job,Artifact raw,boolean waiting){}
    public boolean canResubmit(long videoId){
        var list=jobs.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<AsrJob>query().eq("video_id",videoId).orderByDesc("id").last("LIMIT 1"));
        return !list.isEmpty() && list.getFirst().submissionNo<2 && list.getFirst().providerTaskId==null
            && ("SUBMISSION_UNKNOWN".equals(list.getFirst().status)||"SUBMITTING".equals(list.getFirst().status));
    }
}
