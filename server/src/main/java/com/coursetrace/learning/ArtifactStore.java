package com.coursetrace.learning;

import com.coursetrace.storage.StorageProperties;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;

@Component
public class ArtifactStore {
    private final ArtifactMapper artifacts;
    private final MediaStorage storage;
    private final StorageProperties settings;
    private final Clock clock;
    public ArtifactStore(ArtifactMapper artifacts,MediaStorage storage,StorageProperties settings,Clock clock){
        this.artifacts=artifacts;this.storage=storage;this.settings=settings;this.clock=clock;
    }
    public Artifact ready(long videoId,String kind,String fingerprint){
        var a=artifacts.find(videoId,kind,fingerprint);
        return a!=null && "READY".equals(a.state)?a:null;
    }
    public Artifact byId(long id){
        var a=artifacts.selectById(id);
        if(a==null || !"READY".equals(a.state))throw LearningFailure.permanent("ARTIFACT_MISSING","处理产物尚未就绪");
        return a;
    }
    public Artifact prepare(long videoId,String kind,String fingerprint,Path path,String type,String metadata){
        var a=new Artifact();
        try{
            a.videoId=videoId;a.kind=kind;a.fingerprint=fingerprint;a.bucketName=settings.bucket();
            a.objectKey="learning/"+videoId+"/"+kind.toLowerCase()+"/"+UUID.randomUUID()+("audio/flac".equals(type)?".flac":".json");
            a.sizeBytes=Files.size(path);
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
            a.sha256=java.util.HexFormat.of().formatHex(digest.digest());a.metadataJson=metadata;a.state="READY";
            a.createdAt=LocalDateTime.now(clock);a.updatedAt=a.createdAt;
            storage.put(a.bucketName,a.objectKey,path,type);return a;
        }catch(LearningFailure e){discardUnreferenced(a);throw e;}catch(Exception e){discardUnreferenced(a);throw LearningFailure.permanent("ARTIFACT_WRITE_FAILED","无法保存处理文件");}
    }
    public Artifact prepareJson(long videoId,String kind,String fingerprint,String body,String metadata){
        Path temp=null;
        try{temp=Files.createTempFile("coursetrace-result-",".json");Files.writeString(temp,body,StandardCharsets.UTF_8);return prepare(videoId,kind,fingerprint,temp,"application/json",metadata);}
        catch(java.io.IOException e){throw LearningFailure.permanent("ARTIFACT_WRITE_FAILED","无法保存处理结果");}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(Exception ignored){}}
    }
    // Called inside the stage completion transaction, together with step state and next Outbox.
    public Artifact save(Artifact candidate){
        var existing=artifacts.find(candidate.videoId,candidate.kind,candidate.fingerprint);
        if(existing!=null && "READY".equals(existing.state))return existing;
        if(existing==null)artifacts.insert(candidate);
        else{
            candidate.id=existing.id;candidate.requestCount=existing.requestCount;
            candidate.inputTokens=existing.inputTokens;candidate.outputTokens=existing.outputTokens;
            artifacts.updateById(candidate);
        }
        return candidate;
    }
    public String text(Artifact artifact){var bytes=storage.bytes(artifact.bucketName,artifact.objectKey,4*1024*1024);if(bytes.length!=artifact.sizeBytes || !LearningJson.hash(bytes).equals(artifact.sha256))throw LearningFailure.permanent("ARTIFACT_CORRUPT","处理结果校验失败");return new String(bytes,StandardCharsets.UTF_8);}
    public static String fileHash(Path path){
        try{
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(Exception e){throw LearningFailure.permanent("ARTIFACT_READ_FAILED","无法校验处理文件");}
    }
    public void discardUnreferenced(Artifact candidate){
        if(candidate.objectKey==null||candidate.bucketName==null)return;
        try{if(artifacts.references(candidate.bucketName,candidate.objectKey)==0)storage.remove(candidate.bucketName,candidate.objectKey);}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(ArtifactStore.class).warn("Artifact cleanup needs inspection: video={}, key={}",candidate.videoId,candidate.objectKey);}
    }
}
