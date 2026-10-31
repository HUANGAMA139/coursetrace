package com.coursetrace.learning;

import com.coursetrace.video.*;
import com.coursetrace.processing.*;
import com.coursetrace.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import java.util.*;

@Service @Transactional(readOnly=true)
public class LearningReadService {
    private final VideoService videos;
    private final ProcessingMapper tasks;
    private final TaskStepMapper steps;
    private final SegmentMapper segments;
    private final NoteMapper notes;
    private final LearningJson json;
    public LearningReadService(VideoService videos,ProcessingMapper tasks,TaskStepMapper steps,SegmentMapper segments,NoteMapper notes,LearningJson json){
        this.videos=videos;this.tasks=tasks;this.steps=steps;this.segments=segments;this.notes=notes;this.json=json;
    }
    private Context context(long owner,long videoId){
        var video=videos.owned(owner,videoId);var task=tasks.findVersion(videoId,2);
        if(task==null)return new Context(video,null,null);
        var step=steps.latestSucceeded(task.id,"TRANSCRIPT_BUILD");
        return new Context(video,task,step);
    }
    public Map<String,Object> transcript(long owner,long videoId){
        var c=context(owner,videoId);
        if(c.step==null)return Map.of("ready",false);
        var result=json.read(c.step.resultJson);
        return Map.of("ready",true,"version",result.path("transcriptArtifactId").asLong(),"segmentCount",result.path("segmentCount").asInt(),
            "windowCount",result.path("windowCount").asInt(),"durationMs",c.video.getDurationMs());
    }
    public Map<String,Object> segments(long owner,long videoId,int page,int size,Long atMs){
        var c=context(owner,videoId);if(c.step==null)return Map.of("items",List.of(),"total",0,"page",1,"size",size);
        long version=json.read(c.step.resultJson).path("transcriptArtifactId").asLong();var all=segments.transcript(version);
        if(atMs!=null)for(int i=0;i<all.size();i++)if(all.get(i).endMs>atMs){page=i/size+1;break;}
        int start=Math.min((page-1)*size,all.size()),end=Math.min(start+size,all.size());
        return Map.of("items",all.subList(start,end).stream().map(s->Map.of("id",s.id,"beginMs",s.beginMs,"endMs",s.endMs,"text",s.text)).toList(),
            "total",all.size(),"page",page,"size",size,"version",version);
    }
    public Map<String,Object> notes(long owner,long videoId){
        var c=context(owner,videoId);if(c.step==null)return Map.of("items",List.of(),"complete",false,"completedWindows",0);
        long version=json.read(c.step.resultJson).path("transcriptArtifactId").asLong();
        var config=json.config(c.task.parametersJson);var found=notes.notes(videoId,version,config.chatModel(),config.promptVersion());
        var byId=new HashMap<Long,Segment>();for(var s:segments.transcript(version))byId.put(s.id,s);
        var items=new ArrayList<Map<String,Object>>();
        for(var note:found){
            var sources=new ArrayList<Map<String,Object>>();
            for(var id:json.read(note.sourceSegmentIdsJson)){
                var source=byId.get(id.asLong());if(source==null)throw new IllegalStateException("Invalid persisted note source");
                sources.add(Map.of("segmentId",source.id,"beginMs",source.beginMs,"endMs",source.endMs,"quote",source.text.substring(0,source.text.offsetByCodePoints(0,Math.min(160,source.text.codePointCount(0,source.text.length()))))));
            }
            items.add(Map.of("id",note.id,"windowNo",note.windowNo,"title",note.title,"summary",note.summary,"points",json.read(note.pointsJson),
                "beginMs",note.beginMs,"endMs",note.endMs,"sources",sources));
        }
        long completed=found.stream().map(n->n.windowNo).distinct().count();
        int expected=json.read(c.step.resultJson).path("windowCount").asInt();
        return Map.of("items",items,"version",version,"completedWindows",completed,"totalWindows",expected,"complete",completed>=expected);
    }
    public String vtt(long owner,long videoId,Long requestedVersion){
        var c=context(owner,videoId);
        if(c.step==null)throw new ApiException(HttpStatus.CONFLICT,"TRANSCRIPT_NOT_READY","字幕尚未就绪");
        long version=json.read(c.step.resultJson).path("transcriptArtifactId").asLong();
        if(requestedVersion!=null&&requestedVersion!=version)throw new ApiException(HttpStatus.CONFLICT,"TRANSCRIPT_CHANGED","字幕版本已变化，请刷新");
        var output=new StringBuilder("WEBVTT\n\n");
        for(var s:segments.transcript(version)){
            String text=s.text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace('\r',' ').replace('\n',' ');
            output.append(s.id).append('\n').append(time(s.beginMs)).append(" --> ").append(time(s.endMs)).append('\n').append(text).append("\n\n");
        }
        return output.toString();
    }
    private String time(long ms){return String.format(Locale.ROOT,"%02d:%02d:%02d.%03d",ms/3600000,(ms/60000)%60,(ms/1000)%60,ms%1000);}
    private record Context(CourseVideo video,ProcessingTask task,TaskStep step){}
}
