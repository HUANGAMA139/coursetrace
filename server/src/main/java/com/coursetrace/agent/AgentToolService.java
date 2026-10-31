package com.coursetrace.agent;
import com.coursetrace.learning.*;
import com.coursetrace.retrieval.*;
import com.coursetrace.retrieval.SearchModels.SearchIndex;
import com.coursetrace.retrieval.RetrievalProperties.Options;
import com.coursetrace.retrieval.RetrievalService.Evidence;
import org.springframework.stereotype.Service;
import java.util.*;
import java.nio.charset.StandardCharsets;
@Service public class AgentToolService {
 private final LearningJson json;private final QwenEmbeddingClient embeddings;
 private final IndexSource source;private final RetrievalService retrieval;private final ContextReadService context;
 public AgentToolService(LearningJson json,QwenEmbeddingClient embeddings,RetrievalService retrieval,ContextReadService context,IndexSource source){
  this.source=source;this.json=json;this.embeddings=embeddings;this.retrieval=retrieval;this.context=context;
 }
 public record Call(String name,String args,String key,String query,int topK,long anchor,int before,int after,int offset){}
 public record Output(String json,List<Evidence> evidence,long inputTokens){}
 public Call prepare(String name,String raw,EvidenceLedger ledger){
  if(!Set.of("searchSegments","readSurroundingContext").contains(name))throw denied("TOOL_NOT_ALLOWED");
  if(raw==null||raw.length()>3000)throw invalid();
  var node=json.read(raw);if(!node.isObject())throw invalid();
  boolean search=name.equals("searchSegments");var allowed=search?Set.of("query","topK"):Set.of("segmentId","before","after","startOffset");
  for(var it=node.fieldNames();it.hasNext();)if(!allowed.contains(it.next()))throw denied("TOOL_SCOPE_FORBIDDEN");
  if(search){
   var query=node.path("query");if(!query.isTextual()||query.asText().isBlank()||query.asText().codePointCount(0,query.asText().length())>500||bytes(query.asText())>3000)throw invalid();
   String q=query.asText().strip();int k=integer(node,"topK",2,1,3);
   String args=json.write(new TreeMap<>(Map.of("query",q,"topK",k)));
   return new Call(name,args,name+":"+args,q,k,0,0,0,0);
  }
  var id=node.path("segmentId");if(!id.isIntegralNumber()||!id.canConvertToLong()||id.asLong()<1)throw invalid();
  if(!ledger.contains(id.asLong()))throw denied("TOOL_SOURCE_FORBIDDEN");
  int before=integer(node,"before",2,0,3),after=integer(node,"after",2,0,3),offset=integer(node,"startOffset",0,0,1000000);
  String args=json.write(new TreeMap<>(Map.of("segmentId",id.asLong(),"before",before,"after",after,"startOffset",offset)));
  return new Call(name,args,name+":"+args,null,0,id.asLong(),before,after,offset);
 }
 private int integer(com.fasterxml.jackson.databind.JsonNode n,String field,int fallback,int min,int max){
  if(!n.has(field)||n.path(field).isNull())return fallback;var value=n.get(field);
  if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<min||value.asInt()>max)throw LearningFailure.permanent("TOOL_INVALID_ARGUMENT",field+" 必须是 "+min+"～"+max+" 的 JSON 整数，不得使用带引号的字符串");return value.asInt();
 }
 public Output execute(Call call,SearchIndex index,Options config,int seconds){
  List<Evidence> evidence;long tokens=0;
  if(call.name.equals("searchSegments")){
   var vector=embeddings.embed(config,List.of(call.query),Math.max(1,Math.min(15,seconds)));tokens=vector.inputTokens();
   var c=new Options(config.embeddingModel(),config.dimensions(),config.collection(),config.targetChars(),config.maxChars(),config.maxBytes(),config.overlapChars(),config.batchSize(),config.maxChunks(),call.topK,config.chunkerVersion(),config.embeddingBase(),config.chatModel(),config.promptVersion());
   evidence=focus(retrieval.searchChunks(index,vector.vectors().getFirst(),c),call.query);
  }else evidence=context.read(index,call.anchor,call.before,call.after,call.offset);
  var lengths=new HashMap<Long,Integer>();for(var segment:source.read(index.ownerId,index.videoId,index.transcriptArtifactId))lengths.put(segment.id,segment.text.length());
  return bounded(evidence,tokens,12000,lengths);
 }
 // Rank fragments inside the already validated vector hits before applying the output budget.
 // This does not fetch neighbors: only readSurroundingContext can add those sources.
 public List<Evidence> focus(List<Evidence> evidence,String query){
  var terms=new LinkedHashSet<String>();String normalized=query.toLowerCase(Locale.ROOT);
  var matcher=java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+").matcher(normalized);
  while(matcher.find()){
   String word=matcher.group();if(word.codePoints().allMatch(c->c<128)){if(word.length()>1)terms.add(word);}
   else{int[] chars=word.codePoints().toArray();for(int n=0;n+1<chars.length;n++)terms.add(new String(chars,n,2));}
  }
  java.util.function.ToIntFunction<Evidence> score=e->{
   String text=e.text().toLowerCase(Locale.ROOT);int total=text.contains(normalized)?10:0;
   for(String term:terms)if(text.contains(term))total++;return total;
  };
  var matches=evidence.stream().filter(e->score.applyAsInt(e)>0).sorted(Comparator.comparingInt(score).reversed()).toList();
  return matches.isEmpty()?evidence:matches;
 }
 public Output bounded(List<Evidence> incoming,long tokens){return bounded(incoming,tokens,12000);}
 public Output bounded(List<Evidence> incoming,long tokens,int maxBytes){return bounded(incoming,tokens,maxBytes,Map.of());}
 private Output bounded(List<Evidence> incoming,long tokens,int maxBytes,Map<Long,Integer> lengths){
  var result=new ArrayList<Evidence>();var seen=new HashSet<String>();boolean truncated=false;
  for(var e:incoming){
   if(!seen.add(e.segmentId()+":"+e.startOffset()+":"+e.endOffset()))continue;
   int end=Math.min(e.text().length(),1100);
   if(end<e.text().length()&&Character.isHighSurrogate(e.text().charAt(end-1)))end--;
   var piece=new Evidence(e.segmentId(),e.startOffset(),e.startOffset()+end,e.text().substring(0,end),e.beginMs(),e.endMs());
   result.add(piece);String candidate=encode(result,true,lengths);
   if(candidate.length()>2500||bytes(candidate)>maxBytes){result.removeLast();truncated=true;break;}
   if(end<e.text().length())truncated=true;
  }
  if(result.size()<incoming.size())truncated=true;
  return new Output(encode(result,truncated,lengths),List.copyOf(result),tokens);
 }
 private String encode(List<Evidence> evidence,boolean truncated,Map<Long,Integer> lengths){
  return json.write(Map.of("sources",evidence.stream().map(e->Map.of("segmentId",e.segmentId(),"startOffset",e.startOffset(),"endOffset",e.endOffset(),"text",e.text(),"beginMs",e.beginMs(),"endMs",e.endMs(),"nextOffset",e.endOffset()<lengths.getOrDefault(e.segmentId(),e.endOffset())?e.endOffset():-1)).toList(),"truncated",truncated,"hasMoreContext",true));
 }
 public static int bytes(String text){return text.getBytes(StandardCharsets.UTF_8).length;}
 private LearningFailure invalid(){return LearningFailure.permanent("TOOL_INVALID_ARGUMENT","工具参数不符合约束，请修正参数");}
 private LearningFailure denied(String code){return LearningFailure.permanent(code,"工具请求超出当前课程或已提供材料的范围");}
}
