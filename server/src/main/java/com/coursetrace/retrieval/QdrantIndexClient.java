package com.coursetrace.retrieval;
import com.coursetrace.learning.*;
import com.coursetrace.retrieval.SearchModels.*;
import com.coursetrace.retrieval.RetrievalProperties.Options;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.*;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

@Component public class QdrantIndexClient {
 private final RetrievalProperties properties;
 private final LearningJson json;
 private final OkHttpClient client=new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(2))
  .readTimeout(Duration.ofSeconds(10)).callTimeout(Duration.ofSeconds(10)).retryOnConnectionFailure(true)
  .followRedirects(false).followSslRedirects(false).build();
 public QdrantIndexClient(RetrievalProperties properties,LearningJson json){this.properties=properties;this.json=json;}
 public boolean available(){try{call("GET","/readyz",null,false);return true;}catch(RuntimeException e){return false;}}
 public void ensure(Options config){
  String path="/collections/"+config.collection();
  var existing=call("GET",path,null,true);
  if(existing==null){call("PUT",path,Map.of("vectors",Map.of("size",config.dimensions(),"distance","Cosine")),false);existing=call("GET",path,null,false);}
  var vectors=existing.path("result").path("config").path("params").path("vectors");
  if(vectors.path("size").asInt()!=config.dimensions()||!"Cosine".equals(vectors.path("distance").asText()))
   throw LearningFailure.permanent("INDEX_SCHEMA_MISMATCH","向量集合配置不匹配，请使用新的集合名");
  for(String field:List.of("owner_id","course_id","video_id","transcript_artifact_id","index_id","chunk_id"))
   call("PUT",path+"/index?wait=true",Map.of("field_name",field,"field_schema","integer"),false);
 }
 public Map<String,Object> payload(SearchIndex index,Chunk chunk,Options config){
  return Map.of("owner_id",index.ownerId,"course_id",index.courseId,"video_id",index.videoId,
   "transcript_artifact_id",index.transcriptArtifactId,"index_id",index.id,"chunk_id",chunk.id,
   "embedding_version",config.embeddingModel()+":"+config.dimensions(),"chunker_version",config.chunkerVersion(),"content_hash",chunk.contentHash);
 }
 public void upsert(SearchIndex index,List<Chunk> chunks,List<float[]> vectors,Options config){
  QwenEmbeddingClient.validate(vectors,chunks.size(),config.dimensions());
  var points=new ArrayList<Map<String,Object>>();
  for(int n=0;n<chunks.size();n++)points.add(Map.of("id",chunks.get(n).pointId,"vector",vectors.get(n),"payload",payload(index,chunks.get(n),config)));
  var result=call("PUT","/collections/"+index.collectionName+"/points?wait=true",Map.of("points",points),false);
  if(!"completed".equals(result.path("result").path("status").asText()))throw new LearningFailure("INDEX_WRITE_PENDING","向量写入尚未确认完成",true);
 }
 public Map<String,Object> filter(SearchIndex index){
  var must=new ArrayList<Map<String,Object>>();
  for(var entry:Map.of("owner_id",index.ownerId,"course_id",index.courseId,"video_id",index.videoId,
   "transcript_artifact_id",index.transcriptArtifactId,"index_id",index.id).entrySet())
   must.add(Map.of("key",entry.getKey(),"match",Map.of("value",entry.getValue())));
  return Map.of("must",must);
 }
 public boolean complete(SearchIndex index,List<Chunk> chunks,Options config){
  if(chunks.isEmpty())return false;
  var count=call("POST","/collections/"+index.collectionName+"/points/count",Map.of("filter",filter(index),"exact",true),false);
  if(count.path("result").path("count").asInt(-1)!=chunks.size())return false;
  var result=call("POST","/collections/"+index.collectionName+"/points",Map.of("ids",chunks.stream().map(c->c.pointId).toList(),"with_payload",true,"with_vector",false),false).path("result");
  if(!result.isArray()||result.size()!=chunks.size())return false;
  var byPoint=new HashMap<String,Chunk>();for(var c:chunks)byPoint.put(c.pointId,c);
  for(var point:result){var chunk=byPoint.remove(point.path("id").asText());if(chunk==null||!matches(point.path("payload"),index,chunk,config))return false;}
  return byPoint.isEmpty();
 }
 public List<Hit> query(SearchIndex index,float[] vector,Options config){
  QwenEmbeddingClient.validate(List.of(vector),1,config.dimensions());
  var points=call("POST","/collections/"+index.collectionName+"/points/query",
   Map.of("query",vector,"filter",filter(index),"limit",config.topK(),"with_payload",true,"with_vector",false),false).path("result").path("points");
  if(!points.isArray())throw new LearningFailure("INDEX_RESPONSE_INVALID","检索服务响应格式不正确",true);
  var hits=new ArrayList<Hit>();
  for(var point:points) {
   double score=point.path("score").asDouble(Double.NaN);
   if(Double.isFinite(score))hits.add(new Hit(point.path("id").asText(),point.path("payload").path("chunk_id").asLong(),score,point.path("payload")));
  }
  return hits;
 }
 public boolean matches(JsonNode payload,SearchIndex i,Chunk c,Options config){
  return payload.path("owner_id").asLong()==i.ownerId&&payload.path("course_id").asLong()==i.courseId&&payload.path("video_id").asLong()==i.videoId
   &&payload.path("transcript_artifact_id").asLong()==i.transcriptArtifactId&&payload.path("index_id").asLong()==i.id&&payload.path("chunk_id").asLong()==c.id
   &&payload.path("content_hash").asText().equals(c.contentHash)
   &&payload.path("embedding_version").asText().equals(config.embeddingModel()+":"+config.dimensions())
   &&payload.path("chunker_version").asText().equals(config.chunkerVersion());
 }
 private JsonNode call(String method,String path,Object body,boolean allowMissing){
  try{
   String base=properties.getQdrantUrl().replaceAll("/+$","");
   var uri=java.net.URI.create(base);
   if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null)throw new IllegalArgumentException();
   var request=new Request.Builder().url(base+path).method(method,body==null?null:RequestBody.create(json.write(body),MediaType.get("application/json"))).build();
   try(var response=client.newCall(request).execute()){
    if(response.code()==404){if(allowMissing)return null;throw LearningFailure.permanent("INDEX_MISSING","检索索引不存在，请重新建立");}
    if(!response.isSuccessful()){
     if(response.code()>=500||response.code()==429)throw new LearningFailure("QDRANT_UNAVAILABLE","检索服务暂时不可用",true);
     throw LearningFailure.permanent("INDEX_REQUEST_REJECTED","检索服务拒绝请求，请检查索引配置");
    }
    var input=response.body();if(input==null)throw new java.io.IOException();
    byte[] bytes=input.byteStream().readNBytes(2*1024*1024+1);
    if(bytes.length>2*1024*1024)throw LearningFailure.permanent("INDEX_RESPONSE_TOO_LARGE","检索服务响应超过限制");
    if(path.equals("/readyz"))return json.read("{}");
    return json.read(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
   }
  }catch(LearningFailure e){throw e;}catch(Exception e){org.slf4j.LoggerFactory.getLogger(QdrantIndexClient.class).warn("Qdrant transport failure: type={}, origin={}",e.getClass().getSimpleName(),e.getStackTrace().length==0?"unknown":e.getStackTrace()[0]);throw new LearningFailure("QDRANT_UNAVAILABLE","检索服务暂时不可用，请检查 Qdrant",true);}
 }
 public record Hit(String pointId,long chunkId,double score,JsonNode payload){}
}
