package com.coursetrace.learning;

import com.coursetrace.video.CourseVideo;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class TranscriptService {
    private final SegmentMapper segments;
    private final LearningJson json;
    private final Clock clock;
    public TranscriptService(SegmentMapper segments,LearningJson json,Clock clock){this.segments=segments;this.json=json;this.clock=clock;}
    public List<Draft> prepare(String raw,Artifact audio,CourseVideo video,LearningConfig config){
        long origin=json.read(audio.metadataJson).path("audioOriginMs").asLong();
        var result=new ArrayList<Draft>();long previous=-1;int chars=0;
        var root=json.read(raw);
        for(var channel:root.path("transcripts")){
            if(channel.path("channel_id").asInt(0)!=0)continue;
            for(var sentence:channel.path("sentences")){
                String text=sentence.path("text").asText().strip();
                var begin=sentence.path("begin_time");var end=sentence.path("end_time");
                if(text.isBlank())continue;
                if(!begin.isIntegralNumber()||!end.isIntegralNumber()||!begin.canConvertToLong()||!end.canConvertToLong())
                    throw invalid();
                long start=Math.addExact(origin,begin.longValue()),finish=Math.addExact(origin,end.longValue());
                if(start< -200 || start<previous || finish<=start || finish>video.getDurationMs()+200 || text.length()>config.windowChars())throw invalid();
                start=Math.max(0,start);finish=Math.min(video.getDurationMs(),finish);
                if(finish<=start)throw invalid();
                result.add(new Draft(start,finish,text));previous=start;chars+=text.length();
                if(result.size()>10000 || chars>config.windowChars()*config.maxWindows())
                    throw LearningFailure.permanent("TRANSCRIPT_TOO_LARGE","字幕长度超过本阶段笔记处理预算，请使用较短的视频");
            }
        }
        if(result.isEmpty())throw LearningFailure.permanent("NO_SPEECH","未识别到可用语音，仍可播放原视频");
        return result;
    }
    public void save(long videoId,long artifactId,List<Draft> drafts){
        var existing=segments.transcript(artifactId);
        if(!existing.isEmpty()){
            if(existing.size()!=drafts.size())throw LearningFailure.permanent("TRANSCRIPT_CONFLICT","已有字幕与转录版本不一致");
            return;
        }
        int index=0;
        for(var draft:drafts){
            var s=new Segment();s.videoId=videoId;s.transcriptArtifactId=artifactId;s.ordinal=++index;
            s.beginMs=draft.beginMs;s.endMs=draft.endMs;s.text=draft.text;s.createdAt=LocalDateTime.now(clock);segments.insert(s);
        }
    }
    public List<List<Segment>> windows(long artifactId,LearningConfig config){
        var all=segments.transcript(artifactId);if(all.isEmpty())throw LearningFailure.permanent("TRANSCRIPT_MISSING","字幕尚未就绪");
        var windows=new ArrayList<List<Segment>>();var current=new ArrayList<Segment>();int chars=0,bytes=2;
        for(var segment:all){
            if((chars+segment.text.length()>config.windowChars()||bytes+inputCost(segment.text)>30000)&&!current.isEmpty()){windows.add(List.copyOf(current));current.clear();chars=0;bytes=2;}
            current.add(segment);chars+=segment.text.length();bytes+=inputCost(segment.text);
        }
        if(!current.isEmpty())windows.add(List.copyOf(current));
        if(windows.size()>config.maxWindows())throw LearningFailure.permanent("NOTE_INPUT_TOO_LARGE","笔记窗口数量超过预算");
        return windows;
    }
    public int windowCount(List<Draft> drafts,LearningConfig config){
        int count=1,chars=0,bytes=2;
        for(var draft:drafts){if((chars+draft.text.length()>config.windowChars()||bytes+inputCost(draft.text)>30000)&&chars>0){count++;chars=0;bytes=2;}chars+=draft.text.length();bytes+=inputCost(draft.text);}
        if(count>config.maxWindows())throw LearningFailure.permanent("NOTE_INPUT_TOO_LARGE","笔记窗口数量超过预算");return count;
    }
    private LearningFailure invalid(){return LearningFailure.permanent("INVALID_TRANSCRIPT","转录结果的文本或时间戳不正确");}
    public record Draft(long beginMs,long endMs,String text){}
    private int inputCost(String text){return json.write(Map.of("segmentId",Long.MAX_VALUE,"text",text)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length+1;}
}
