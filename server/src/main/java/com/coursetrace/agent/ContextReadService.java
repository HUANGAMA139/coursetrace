package com.coursetrace.agent;
import com.coursetrace.retrieval.*;
import com.coursetrace.retrieval.SearchModels.SearchIndex;
import com.coursetrace.retrieval.RetrievalService.Evidence;
import com.coursetrace.learning.*;
import org.springframework.stereotype.Service;
import java.util.*;
@Service public class ContextReadService {
 private final IndexSource source;
 public ContextReadService(IndexSource source){this.source=source;}
 public List<Evidence> read(SearchIndex index,long anchor,int before,int after,int start){
  var all=source.read(index.ownerId,index.videoId,index.transcriptArtifactId);
  int at=-1;for(int n=0;n<all.size();n++)if(all.get(n).id==anchor){at=n;break;}
  if(at<0)throw LearningFailure.permanent("TOOL_SOURCE_FORBIDDEN","补读片段不属于当前课程资料");
  var result=new ArrayList<Evidence>();
  // Anchor first so a long preceding sentence cannot crowd out the requested continuation.
  add(result,all.get(at),start);
  for(int n=Math.max(0,at-before);n<=Math.min(all.size()-1,at+after);n++)if(n!=at)add(result,all.get(n),0);
  return result;
 }
 private void add(List<Evidence> result,Segment s,int start){
  if(start<0||start>=s.text.length()||(start>0&&Character.isLowSurrogate(s.text.charAt(start))))
   throw LearningFailure.permanent("TOOL_INVALID_ARGUMENT","字幕偏移超出有效范围");
  int end=Math.min(s.text.length(),start+1200);if(end<s.text.length()&&Character.isHighSurrogate(s.text.charAt(end-1)))end--;
  result.add(new Evidence(s.id,start,end,s.text.substring(start,end),s.beginMs,s.endMs));
 }
}
