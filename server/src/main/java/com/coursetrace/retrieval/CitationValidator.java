package com.coursetrace.retrieval;
import com.coursetrace.learning.*;
import com.coursetrace.retrieval.SearchModels.SearchIndex;
import com.coursetrace.retrieval.RetrievalService.Evidence;
import org.springframework.stereotype.Component;
import java.util.*;
@Component public class CitationValidator {
 public record Citation(long segmentId,long videoId,long transcriptVersion,long beginMs,long endMs,int startOffset,int endOffset,String quote){}
 public record Paragraph(String text,List<Citation> citations){}
 public record Answer(String status,List<Paragraph> paragraphs,String message){}
 private final LearningJson json;private final IndexSource source;
 public CitationValidator(LearningJson json,IndexSource source){this.json=json;this.source=source;}
 public Answer insufficient(){return new Answer("INSUFFICIENT_EVIDENCE",List.of(),"当前视频中没有找到足够依据。可以换个问法，或先回看相关字幕。");}
 public Answer validate(String raw,SearchIndex index,List<Evidence> evidence){
  try{
   if(raw==null||raw.length()>16000)throw invalid();
   var root=json.read(raw);
   if("INSUFFICIENT_EVIDENCE".equals(root.path("status").asText()))return insufficient();
   if(!"ANSWERED".equals(root.path("status").asText()))throw invalid();
   var paragraphs=root.path("paragraphs");if(!paragraphs.isArray()||paragraphs.isEmpty()||paragraphs.size()>6)throw invalid();
   var allowed=new HashMap<Long,Evidence>();for(var e:evidence)allowed.put(e.segmentId(),e);
   var canonical=new HashMap<Long,Segment>();for(var s:source.read(index.ownerId,index.videoId,index.transcriptArtifactId))canonical.put(s.id,s);
   var result=new ArrayList<Paragraph>();
   for(var paragraph:paragraphs){
    var text=paragraph.path("text");var ids=paragraph.path("sourceSegmentIds");
    if(!text.isTextual()||text.asText().isBlank()||text.asText().length()>1200||!ids.isArray()||ids.isEmpty()||ids.size()>5)throw invalid();
    var citations=new ArrayList<Citation>();var seen=new HashSet<Long>();
    for(var id:ids){
     if(!id.isIntegralNumber()||!id.canConvertToLong())throw invalid();long sourceId=id.asLong();
     var e=allowed.get(sourceId);var segment=canonical.get(sourceId);
     if(e==null)throw invalid();RetrievalService.check(segment,e.startOffset(),e.endOffset());
     if(!segment.text.substring(e.startOffset(),e.endOffset()).equals(e.text())||segment.beginMs!=e.beginMs()||segment.endMs!=e.endMs())throw invalid();
     if(!seen.add(sourceId))continue;
     int end=Math.min(e.text().length(),300);if(end<e.text().length()&&Character.isHighSurrogate(e.text().charAt(end-1)))end--;
     citations.add(new Citation(sourceId,index.videoId,index.transcriptArtifactId,segment.beginMs,segment.endMs,e.startOffset(),e.endOffset(),e.text().substring(0,end)));
    }
    result.add(new Paragraph(text.asText(),List.copyOf(citations)));
   }
   return new Answer("ANSWERED",List.copyOf(result),"");
  }catch(LearningFailure e){throw invalid();}catch(RuntimeException e){throw invalid();}
 }
 private LearningFailure invalid(){return LearningFailure.permanent("INVALID_ANSWER","回答来源未通过校验，请换个问法后重试");}
}
