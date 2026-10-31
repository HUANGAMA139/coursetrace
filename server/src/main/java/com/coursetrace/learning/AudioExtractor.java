package com.coursetrace.learning;

import com.coursetrace.media.MediaProperties;
import com.coursetrace.video.CourseVideo;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigDecimal;

@Component
public class AudioExtractor {
    private final ArtifactStore store;
    private final MediaStorage storage;
    private final MediaProperties media;
    private final LearningProperties settings;
    private final LearningJson json;
    private final Semaphore slots=new Semaphore(1);
    public AudioExtractor(ArtifactStore store,MediaStorage storage,MediaProperties media,LearningProperties settings,LearningJson json){
        this.store=store;this.storage=storage;this.media=media;this.settings=settings;this.json=json;
    }
    public static String fingerprint(CourseVideo video){return LearningJson.hash(video.getId()+":"+video.getBucketName()+":"+video.getObjectKey()+":"+video.getSizeBytes()+":flac-aligned-v1");}
    public Artifact extract(CourseVideo video){
        var fingerprint=fingerprint(video);var existing=store.ready(video.getId(),"AUDIO",fingerprint);
        if(existing!=null){if(storage.size(existing.bucketName,existing.objectKey)!=existing.sizeBytes)throw LearningFailure.permanent("ARTIFACT_CORRUPT","音频处理文件大小不匹配");return existing;}
        if(!slots.tryAcquire())throw new Deferred();
        Path directory=null;
        try{
            var root=Path.of(media.tempDirectory()).toAbsolutePath().normalize();Files.createDirectories(root);
            directory=Files.createTempDirectory(root,"learning-");var source=directory.resolve("source.mp4");var audio=directory.resolve("audio.flac");
            storage.download(video.getBucketName(),video.getObjectKey(),source,video.getSizeBytes());
            if(Files.size(source)!=video.getSizeBytes())throw LearningFailure.permanent("SOURCE_SIZE_MISMATCH","视频文件大小与记录不符");
            // Normalize timestamps and pad missing audio so FLAC t=0 matches the source video's playback t=0.
            run(List.of(settings.getFfmpegPath(),"-hide_banner","-loglevel","error","-nostdin","-y","-copyts","-start_at_zero",
                "-protocol_whitelist","file,pipe","-i",source.toString(),"-map","0:a:0","-vn","-af","aresample=16000:async=1:first_pts=0,apad",
                "-ac","1","-ar","16000","-sample_fmt","s16","-c:a","flac","-t",BigDecimal.valueOf(video.getDurationMs(),3).toPlainString(),audio.toString()),directory.resolve("ffmpeg.log"),180);
            var probe=directory.resolve("audio-probe.json");
            run(List.of(media.ffprobePath(),"-v","error","-show_format","-show_streams","-of","json",audio.toString()),probe,15);
            if(Files.size(probe)>131072)throw LearningFailure.permanent("AUDIO_INVALID","音频校验数据过大");
            var info=json.read(Files.readString(probe));var streams=info.path("streams");
            if(streams.size()!=1 || streams.get(0).path("channels").asInt()!=1 || streams.get(0).path("sample_rate").asInt()!=16000)
                throw LearningFailure.permanent("AUDIO_INVALID","提取音频的采样率或声道不正确");
            long duration=new BigDecimal(info.path("format").path("duration").asText()).movePointRight(3).longValue();
            if(Math.abs(duration-video.getDurationMs())>200)throw LearningFailure.permanent("AUDIO_TIMELINE_MISMATCH","音频与视频时长不一致");
            return store.prepare(video.getId(),"AUDIO",fingerprint,audio,"audio/flac",json.write(Map.of("audioOriginMs",0,"durationMs",duration,"alignment","padded-to-video-zero-v1","sourceSha256",ArtifactStore.fileHash(source))));
        }catch(LearningFailure e){throw e;}catch(Exception e){throw LearningFailure.permanent("AUDIO_EXTRACT_FAILED","音频提取失败，请检查视频和媒体工具");}
        finally{
            if(directory!=null)try(var paths=Files.walk(directory)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}catch(Exception ignored){}
            slots.release();
        }
    }
    private void run(List<String> args,Path output,int seconds){
        Process process=null;
        try{
            process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if(!process.waitFor(seconds,TimeUnit.SECONDS))throw LearningFailure.permanent("MEDIA_TIMEOUT","媒体处理超时，请尝试较短的视频");
            if(process.exitValue()!=0)throw LearningFailure.permanent("AUDIO_EXTRACT_FAILED","媒体处理失败，请检查视频音轨");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new LearningFailure("PROCESS_INTERRUPTED","媒体处理被中断",true);}
        catch(java.io.IOException e){throw LearningFailure.permanent("MEDIA_UNAVAILABLE","找不到或无法运行媒体处理工具");}
        finally{if(process!=null && process.isAlive()){process.destroyForcibly();try{process.waitFor(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
    }
    public static class Deferred extends RuntimeException {}
}
