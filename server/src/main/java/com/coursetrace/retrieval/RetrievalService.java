package com.coursetrace.retrieval;
import com.coursetrace.learning.*;
import com.coursetrace.retrieval.SearchModels.*;
import com.coursetrace.retrieval.RetrievalProperties.Options;
import org.springframework.stereotype.Service;
import java.util.*;

@Service public class RetrievalService {
 public record Evidence(long segmentId,int startOffset,int endOffset,String text,long beginMs,long endMs){}
 private final ChunkMapper chunks;private final IndexSource source;private final QdrantIndexClient qdrant;
 public RetrievalService(ChunkMapper chunks,IndexSource source,QdrantIndexClient qdrant){this.chunks=chunks;this.source=source;this.qdrant=qdrant;}
 public List<Evidence> searchChunks(SearchIndex index,float[] vector,Options config){
  var hits=qdrant.query(index,vector,config);var all=source.read(index.ownerId,index.videoId,index.transcriptArtifactId);
  var byId=new HashMap<Long,Segment>();for(var s:all)byId.put(s.id,s);
  var primary=new ArrayList<Evidence>();var primaryIds=new LinkedHashSet<Long>();
  for(var hit:hits){
   var chunk=chunks.selectById(hit.chunkId());
   if(chunk==null||!chunk.indexId.equals(index.id)||!chunk.pointId.equals(hit.pointId())||!qdrant.matches(hit.payload(),index,chunk,config))
    throw LearningFailure.permanent("INDEX_SOURCE_INVALID","检索来源与字幕不一致，请重新建立索引");
   var text=new ArrayList<String>();var mapped=chunks.sources(chunk.id);
   if(mapped.isEmpty())throw LearningFailure.permanent("INDEX_SOURCE_INVALID","检索块来源映射缺失");
   for(var relation:mapped){
    var segment=byId.get(relation.segmentId);
    check(segment,relation.startOffset,relation.endOffset);
    var snippet=segment.text.substring(relation.startOffset,relation.endOffset);text.add(snippet);
    primary.add(new Evidence(segment.id,relation.startOffset,relation.endOffset,snippet,segment.beginMs,segment.endMs));primaryIds.add(segment.id);
   }
   if(!String.join("\n",text).equals(chunk.text)||!LearningJson.hash(chunk.text).equals(chunk.contentHash))
    throw LearningFailure.permanent("INDEX_SOURCE_INVALID","检索块与原字幕内容不一致");
  }
  return List.copyOf(primary);
 }
 public List<Evidence> retrieve(SearchIndex index,float[] vector,Options config){
  var primary=searchChunks(index,vector,config);
  var primaryIds=new LinkedHashSet<Long>();for(var e:primary)primaryIds.add(e.segmentId());
  var all=source.read(index.ownerId,index.videoId,index.transcriptArtifactId);
  var result=new LinkedHashMap<Long,Evidence>();
  for(var e:primary)add(result,e);
  for(int n=0;n<all.size();n++)if(primaryIds.contains(all.get(n).id)){
   if(n>0)neighbor(result,all.get(n-1));if(n+1<all.size())neighbor(result,all.get(n+1));
  }
  return List.copyOf(result.values());
 }
 private void neighbor(Map<Long,Evidence> result,Segment s){
  int end=Math.min(1200,s.text.length());if(end<s.text.length()&&Character.isHighSurrogate(s.text.charAt(end-1)))end--;
  add(result,new Evidence(s.id,0,end,s.text.substring(0,end),s.beginMs,s.endMs));
 }
 private void add(Map<Long,Evidence> result,Evidence e){
  if(result.containsKey(e.segmentId())||result.size()>=80)return;
  int remaining=8000-result.values().stream().mapToInt(v->v.text().length()).sum();
  int size=Math.min(remaining,e.text().length());if(size<=0)return;
  if(size<e.text().length()&&Character.isHighSurrogate(e.text().charAt(size-1)))size--;
  if(size<=0)return;
  result.put(e.segmentId(),new Evidence(e.segmentId(),e.startOffset(),e.startOffset()+size,e.text().substring(0,size),e.beginMs(),e.endMs()));
 }
 public static void check(Segment s,int start,int end){
  if(s==null||start<0||end<=start||end>s.text.length()||(start>0&&Character.isLowSurrogate(s.text.charAt(start)))||(end<s.text.length()&&Character.isLowSurrogate(s.text.charAt(end))))
   throw LearningFailure.permanent("INDEX_SOURCE_INVALID","引用文本偏移无法验证");
 }
}
