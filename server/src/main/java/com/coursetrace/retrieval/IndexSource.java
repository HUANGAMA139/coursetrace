package com.coursetrace.retrieval;
import com.coursetrace.learning.*;
import com.coursetrace.video.*;
import org.springframework.stereotype.Service;
import java.util.*;
@Service public class IndexSource {
 private final LearningReadService learning;private final SegmentMapper segments;private final VideoService videos;
 public IndexSource(LearningReadService learning,SegmentMapper segments,VideoService videos){this.learning=learning;this.segments=segments;this.videos=videos;}
 public long current(long owner,long video){
  var state=learning.transcript(owner,video);
  return Boolean.TRUE.equals(state.get("ready"))?((Number)state.get("version")).longValue():0;
 }
 public List<Segment> read(long owner,long video,long version){
  var owned=videos.owned(owner,video);var all=segments.transcript(version);
  if(all.isEmpty()||all.size()>10000||all.stream().anyMatch(s->s.videoId!=video||s.transcriptArtifactId!=version||s.endMs>owned.getDurationMs()+200))
   throw LearningFailure.permanent("INVALID_TRANSCRIPT","字幕版本或视频归属不正确");
  return all;
 }
}
