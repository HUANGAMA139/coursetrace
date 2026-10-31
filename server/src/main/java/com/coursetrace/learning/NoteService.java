package com.coursetrace.learning;

import com.coursetrace.processing.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class NoteService {
    private final ArtifactMapper artifacts;
    private final ArtifactStore store;
    private final NoteMapper notes;
    private final NoteModelClient model;
    private final ProcessingMapper tasks;
    private final LearningJson json;
    private final Clock clock;
    private final TransactionTemplate tx;
    public NoteService(ArtifactMapper artifacts,ArtifactStore store,NoteMapper notes,NoteModelClient model,
                       ProcessingMapper tasks,LearningJson json,Clock clock,PlatformTransactionManager manager){
        this.artifacts=artifacts;this.store=store;this.notes=notes;this.model=model;this.tasks=tasks;this.json=json;this.clock=clock;this.tx=new TransactionTemplate(manager);
    }
    public static String digest(long transcriptId,int window,LearningConfig config){return LearningJson.hash(transcriptId+":"+window+":"+config.chatModel()+":"+config.promptVersion());}
    public Prepared generate(ProcessingTask task,long transcriptId,int window,List<Segment> source,LearningConfig config){
        String fingerprint=digest(transcriptId,window,config);
        var artifact=tx.execute(status->{
            var locked=tasks.lock(task.id);if(locked.runNo!=task.runNo)throw LearningFailure.permanent("TASK_CHANGED","任务轮次已变化");
            var existing=artifacts.find(task.videoId,"NOTE",fingerprint);
            if(existing!=null)return existing;
            var a=new Artifact();a.videoId=task.videoId;a.kind="NOTE";a.fingerprint=fingerprint;a.state="PENDING";
            a.createdAt=LocalDateTime.now(clock);a.updatedAt=a.createdAt;artifacts.insert(a);return a;
        });
        if("READY".equals(artifact.state)){
            var parsed=validate(store.text(artifact),source,task.videoId,transcriptId,window,fingerprint,config);
            return new Prepared(artifact,parsed);
        }
        for(int pass=0;pass<2;pass++){
            artifact=artifacts.selectById(artifact.id);
            if(artifact.responseJson!=null){
                try{
                    var parsed=validate(artifact.responseJson,source,task.videoId,transcriptId,window,fingerprint,config);
                    var candidate=store.prepareJson(task.videoId,"NOTE",fingerprint,artifact.responseJson,"{}");
                    candidate.requestCount=artifact.requestCount;candidate.inputTokens=artifact.inputTokens;candidate.outputTokens=artifact.outputTokens;
                    return new Prepared(candidate,parsed);
                }catch(LearningFailure invalid){
                    if(!"INVALID_NOTE".equals(invalid.code))throw invalid;
                }
            }
            final long artifactId=artifact.id;
            boolean repair=artifact.requestCount>0;
            tx.executeWithoutResult(status->{
                var locked=tasks.lock(task.id);
                if(locked.runNo!=task.runNo)throw LearningFailure.permanent("TASK_CHANGED","任务轮次已变化");
                var current=artifacts.lock(artifactId);
                if(current.requestCount>=2 || artifacts.noteCalls(task.videoId)>=config.maxWindows()*2)
                    throw LearningFailure.permanent("NOTE_BUDGET","笔记请求预算已耗尽，请检查模型配置或输入内容");
                current.requestCount++;current.updatedAt=LocalDateTime.now(clock);artifacts.updateById(current);
            });
            var response=model.generate(config,source,repair);
            var current=artifacts.selectById(artifactId);current.responseJson=response.text();
            current.inputTokens+=response.inputTokens();current.outputTokens+=response.outputTokens();current.updatedAt=LocalDateTime.now(clock);
            artifacts.updateById(current);
        }
        artifact=artifacts.selectById(artifact.id);
        var parsed=validate(artifact.responseJson,source,task.videoId,transcriptId,window,fingerprint,config);
        var candidate=store.prepareJson(task.videoId,"NOTE",fingerprint,artifact.responseJson,"{}");
        candidate.requestCount=artifact.requestCount;candidate.inputTokens=artifact.inputTokens;candidate.outputTokens=artifact.outputTokens;
        return new Prepared(candidate,parsed);
    }
    public List<CourseNote> validate(String body,List<Segment> source,long videoId,long transcriptId,int window,String digest,LearningConfig config){
        try{
            var root=json.read(body);var chapters=root.path("chapters");
            if(!root.isObject()||!chapters.isArray()||chapters.isEmpty()||chapters.size()>8)throw invalid();
            var allowed=source.stream().collect(Collectors.toMap(s->s.id,s->s));var result=new ArrayList<CourseNote>();int index=0;
            for(var chapter:chapters){
                String title=text(chapter,"title",120),summary=text(chapter,"summary",1000);
                var points=chapter.path("points");var refs=chapter.path("sourceSegmentIds");
                if(!points.isArray()||points.isEmpty()||points.size()>8||!refs.isArray()||refs.isEmpty()||refs.size()>15)throw invalid();
                var bullets=new ArrayList<String>();
                for(var point:points){if(!point.isTextual()||point.asText().isBlank()||point.asText().length()>400)throw invalid();bullets.add(point.asText().strip());}
                var ids=new LinkedHashSet<Long>();
                for(var ref:refs){if(!ref.isIntegralNumber()||!ref.canConvertToLong()||!allowed.containsKey(ref.longValue()))throw invalid();ids.add(ref.longValue());}
                var note=new CourseNote();note.videoId=videoId;note.transcriptArtifactId=transcriptId;note.windowNo=window;note.chapterNo=++index;
                note.inputDigest=digest;note.title=title;note.summary=summary;note.pointsJson=json.write(bullets);note.sourceSegmentIdsJson=json.write(ids);
                note.beginMs=ids.stream().map(allowed::get).mapToLong(s->s.beginMs).min().orElseThrow();
                note.endMs=ids.stream().map(allowed::get).mapToLong(s->s.endMs).max().orElseThrow();
                note.model=config.chatModel();note.promptVersion=config.promptVersion();note.createdAt=LocalDateTime.now(clock);result.add(note);
            }
            return result;
        }catch(LearningFailure failure){throw invalid();}catch(Exception failure){throw invalid();}
    }
    public void save(Prepared prepared){
        store.save(prepared.artifact);
        if(notes.window(prepared.notes.getFirst().inputDigest).isEmpty())for(var note:prepared.notes)notes.insert(note);
    }
    private String text(com.fasterxml.jackson.databind.JsonNode node,String field,int max){
        var value=node.path(field);if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>max)throw invalid();return value.asText().strip();
    }
    private LearningFailure invalid(){return LearningFailure.permanent("INVALID_NOTE","笔记结构或来源片段校验失败");}
    public record Prepared(Artifact artifact,List<CourseNote> notes){}
}
