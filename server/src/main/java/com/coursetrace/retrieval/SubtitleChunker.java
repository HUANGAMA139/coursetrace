package com.coursetrace.retrieval;
import com.coursetrace.learning.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.coursetrace.retrieval.RetrievalProperties.Options;

@Component public class SubtitleChunker {
 public record Source(long segmentId,int startOffset,int endOffset){}
 public record Draft(String text,long beginMs,long endMs,List<Source> sources){}
 private record Piece(Segment segment,int start,int end) {
  String text(){return segment.text.substring(start,end);}
 }
 public List<Draft> split(List<Segment> segments,Options config) {
  var pieces=new ArrayList<Piece>();int previous=-1;
  for(var s:segments) {
   if(s.id==null||s.ordinal<=previous||s.text==null||s.text.isBlank()||s.beginMs<0||s.endMs<=s.beginMs)
    throw LearningFailure.permanent("INVALID_TRANSCRIPT","字幕内容或顺序无法用于检索");
   previous=s.ordinal;
   for(int start=0;start<s.text.length();) {
    int end=start,characters=0,bytes=0;
    while(end<s.text.length()&&characters<config.maxChars()) {
     int cp=s.text.codePointAt(end),width=Character.charCount(cp);
     int cost=new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
     if(bytes+cost>config.maxBytes())break;
     end+=width;characters++;bytes+=cost;
    }
    if(end==start)throw LearningFailure.permanent("INVALID_SEARCH_CONFIG","检索块长度配置过小");
    pieces.add(new Piece(s,start,end));start=end;
   }
  }
  var output=new ArrayList<Draft>();int start=0;
  while(start<pieces.size()) {
   int end=start,chars=0,bytes=0;
   while(end<pieces.size()) {
    var text=pieces.get(end).text();int add=text.codePointCount(0,text.length())+(end>start?1:0);
    int addBytes=text.getBytes(StandardCharsets.UTF_8).length+(end>start?1:0);
    if(end>start&&(chars+add>config.maxChars()||bytes+addBytes>config.maxBytes()))break;
    chars+=add;bytes+=addBytes;end++;
    if(chars>=config.targetChars())break;
   }
   var selected=pieces.subList(start,end);
   output.add(new Draft(String.join("\n",selected.stream().map(Piece::text).toList()),
    selected.stream().mapToLong(p->p.segment.beginMs).min().orElseThrow(),
    selected.stream().mapToLong(p->p.segment.endMs).max().orElseThrow(),
    selected.stream().map(p->new Source(p.segment.id,p.start,p.end)).toList()));
   if(output.size()>config.maxChunks())throw LearningFailure.permanent("TOO_MANY_CHUNKS","字幕检索块数量超过本阶段上限");
   int next=end;
   if(end<pieces.size()&&end-start>1) {
    var last=pieces.get(end-1).text();
    if(last.codePointCount(0,last.length())<=config.overlapChars())next=end-1;
   }
   start=next;
  }
  if(output.isEmpty())throw LearningFailure.permanent("NO_TRANSCRIPT","尚无可建立索引的字幕");
  return List.copyOf(output);
 }
}
