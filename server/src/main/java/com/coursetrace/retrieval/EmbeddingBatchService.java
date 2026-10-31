package com.coursetrace.retrieval;
import com.coursetrace.learning.*;
import com.coursetrace.processing.*;
import com.coursetrace.retrieval.SearchModels.*;
import com.coursetrace.retrieval.RetrievalProperties.Options;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

@Service public class EmbeddingBatchService {
 private final ArtifactMapper artifacts;private final ArtifactStore store;private final ProcessingMapper tasks;
 private final QwenEmbeddingClient client;private final LearningJson json;private final Clock clock;private final TransactionTemplate tx;
 public EmbeddingBatchService(ArtifactMapper artifacts,ArtifactStore store,ProcessingMapper tasks,QwenEmbeddingClient client,LearningJson json,Clock clock,PlatformTransactionManager manager){
  this.artifacts=artifacts;this.store=store;this.tasks=tasks;this.client=client;this.json=json;this.clock=clock;tx=new TransactionTemplate(manager);
 }
 public String fingerprint(SearchIndex index,List<Chunk> chunks,Options config){
  return LearningJson.hash(json.write(List.of(index.ownerId,index.videoId,config.embeddingBase(),config.embeddingModel(),config.dimensions(),"plain-text-v1",chunks.stream().map(c->c.contentHash).toList())));
 }
 public Artifact generate(ProcessingTask task,SearchIndex index,List<Chunk> chunks,Options config){
  String hash=fingerprint(index,chunks,config);
  var budget=tx.execute(status->{
   var parent=tasks.lock(task.id);if(parent.runNo!=task.runNo)throw LearningFailure.permanent("TASK_CHANGED","索引任务已变化");
   var current=artifacts.find(task.videoId,"EMBEDDING_JSON",hash);
   if(current==null){current=new Artifact();current.videoId=task.videoId;current.kind="EMBEDDING_JSON";current.fingerprint=hash;current.state="PENDING";current.createdAt=now();current.updatedAt=now();artifacts.insert(current);}
   if(!"READY".equals(current.state)){
    if(current.requestCount>=3)throw LearningFailure.permanent("EMBEDDING_BUDGET","此批向量请求预算已用尽，请检查配置与服务");
    current.requestCount++;current.updatedAt=now();artifacts.updateById(current);
   }
   return current;
  });
  if("READY".equals(budget.state)){vectors(budget,chunks.size(),config.dimensions());return budget;}
  var response=client.embed(config,chunks.stream().map(c->c.text).toList(),45);
  QwenEmbeddingClient.validate(response.vectors(),chunks.size(),config.dimensions());
  var candidate=store.prepareJson(task.videoId,"EMBEDDING_JSON",hash,json.write(Map.of("vectors",response.vectors())),"{}");
  try {
   return tx.execute(status->{
    var current=artifacts.lock(budget.id);
    if("READY".equals(current.state))return current;
    current.inputTokens+=response.inputTokens();current.updatedAt=now();artifacts.updateById(current);
    return store.save(candidate);
   });
  }finally{store.discardUnreferenced(candidate);}
 }
 public Artifact cached(SearchIndex index,List<Chunk> chunks,Options config){
  var cached=store.ready(index.videoId,"EMBEDDING_JSON",fingerprint(index,chunks,config));
  if(cached==null)throw LearningFailure.permanent("EMBEDDING_CHECKPOINT_MISSING","向量检查点缺失，请恢复索引任务");
  return cached;
 }
 public List<float[]> vectors(Artifact artifact,int expected,int dimensions){
  var rows=json.read(store.text(artifact)).path("vectors");var result=new ArrayList<float[]>();
  if(!rows.isArray())throw LearningFailure.permanent("INVALID_EMBEDDING","向量检查点格式不正确");
  for(var row:rows){float[] values=new float[row.size()];for(int n=0;n<values.length;n++){if(!row.get(n).isNumber())throw LearningFailure.permanent("INVALID_EMBEDDING","向量检查点数值不正确");values[n]=(float)row.get(n).asDouble();}result.add(values);}
  QwenEmbeddingClient.validate(result,expected,dimensions);return result;
 }
 private LocalDateTime now(){return LocalDateTime.now(clock);}
}
