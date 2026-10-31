package com.coursetrace.retrieval;
import com.coursetrace.common.ApiException;
import com.coursetrace.learning.*;
import com.coursetrace.processing.*;
import com.coursetrace.outbox.OutboxMapper;
import com.coursetrace.video.*;
import com.coursetrace.retrieval.SearchModels.*;
import com.coursetrace.retrieval.RetrievalProperties.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

@Service public class SearchIndexService {
 private final SearchIndexMapper indexes;private final SearchStateMapper states;private final ChunkMapper chunks;
 private final ProcessingMapper tasks;private final ProcessingService processing;private final OutboxMapper outbox;
 private final IndexSource source;private final IndexWorkflow workflow;private final RetrievalProperties properties;
 private final LearningProperties learning;private final QwenEmbeddingClient embedding;private final QdrantIndexClient qdrant;
 private final com.coursetrace.limit.RedisAdmission admission;
 private final VideoService videos;private final LearningJson json;private final Clock clock;private final TransactionTemplate tx;
 public SearchIndexService(SearchIndexMapper indexes,SearchStateMapper states,ChunkMapper chunks,ProcessingMapper tasks,ProcessingService processing,OutboxMapper outbox,
  IndexSource source,IndexWorkflow workflow,RetrievalProperties properties,LearningProperties learning,QwenEmbeddingClient embedding,QdrantIndexClient qdrant,VideoService videos,LearningJson json,Clock clock,PlatformTransactionManager manager,com.coursetrace.limit.RedisAdmission admission){
  this.admission=admission;
  this.indexes=indexes;this.states=states;this.chunks=chunks;this.tasks=tasks;this.processing=processing;this.outbox=outbox;this.source=source;this.workflow=workflow;
  this.properties=properties;this.learning=learning;this.embedding=embedding;this.qdrant=qdrant;this.videos=videos;this.json=json;this.clock=clock;tx=new TransactionTemplate(manager);
 }
 public ProcessingTaskView submit(long owner,long videoId){return create(owner,videoId,false,0,0,null);}
 public ProcessingTaskView rebuild(long owner,long videoId,long expectedIndexId,int expectedRunNo,String requestId){
  return create(owner,videoId,true,expectedIndexId,expectedRunNo,requestId);
 }
 private ProcessingTaskView create(long owner,long videoId,boolean rebuild,long expectedIndexId,int expectedRunNo,String requestId){
  return tx.execute(transaction->{
   var video=tasks.lockOwnedVideo(owner,videoId);if(video==null)throw error(HttpStatus.NOT_FOUND,"VIDEO_NOT_FOUND","视频不存在或无法访问");
   var task=tasks.findVersion(videoId,3);
   var state=states.selectById(videoId);
   if(rebuild&&requestId!=null&&indexes.request(videoId,requestId)!=null)return processing.get(owner,task.id);
   var config=properties.snapshot(learning);long transcript=source.current(owner,videoId);
   if(transcript==0)throw error(HttpStatus.CONFLICT,"TRANSCRIPT_NOT_READY","请先生成当前视频字幕");
   String fingerprint=LearningJson.hash(json.write(List.of(videoId,transcript,config)));
   if(task!=null){
    task=tasks.lock(task.id);var previous=indexes.selectById(workflow.run(task).indexId());
    if(!rebuild){
     if(!previous.fingerprint.equals(fingerprint))throw error(HttpStatus.CONFLICT,"INDEX_CONFIG_CHANGED","字幕或检索配置已变化，请重新建立索引");
     return processing.get(owner,task.id);
    }
    long current=state.buildingIndexId!=null?state.buildingIndexId:state.activeIndexId==null?0:state.activeIndexId;
    if(task.runNo!=expectedRunNo||current!=expectedIndexId)throw error(HttpStatus.CONFLICT,"INDEX_CHANGED","索引已变化，请刷新后操作");
    if(!Set.of("SUCCEEDED","FAILED").contains(task.status))throw error(HttpStatus.CONFLICT,"INDEX_BUSY","当前索引仍在处理，请等待或恢复任务");
   }else if(rebuild)throw error(HttpStatus.CONFLICT,"INDEX_NOT_STARTED","请先建立索引");
   if(!embedding.configured())throw error(HttpStatus.SERVICE_UNAVAILABLE,"AI_NOT_CONFIGURED","检索模型服务尚未配置");
   admission.admit(owner,"index",rebuild?"rebuild:"+videoId+":"+requestId:"video:"+videoId,fingerprint);
   if(task==null){task=new ProcessingTask();task.videoId=videoId;task.pipelineVersion=3;task.runNo=1;task.status="PENDING";task.currentStage="CHUNK_BUILD";task.createdAt=now();task.updatedAt=now();tasks.insert(task);}
   else{task.runNo++;task.errorCode=null;task.errorMessage=null;task.startedAt=null;task.finishedAt=null;outbox.cancelOld(task.id,task.runNo);}
   if(state==null){state=new SearchState();state.videoId=videoId;state.updatedAt=now();states.insert(state);}
   var index=new SearchIndex();index.videoId=videoId;index.ownerId=owner;index.courseId=video.getCourseId();index.transcriptArtifactId=transcript;index.taskId=task.id;
   index.fingerprint=fingerprint;index.configJson=json.write(config);index.collectionName=config.collection();index.requestId=requestId==null?UUID.randomUUID().toString():requestId;
   index.status="BUILDING";index.createdAt=now();index.updatedAt=now();indexes.insert(index);
   task.parametersJson=json.write(new IndexRun(index.id,config));workflow.startRun(task);
   return processing.get(owner,task.id);
  });
 }
 public Map<String,Object> status(long owner,long videoId){
  videos.owned(owner,videoId);
  long transcript=source.current(owner,videoId);var state=states.selectById(videoId);var task=tasks.findVersion(videoId,3);
  var active=state==null||state.activeIndexId==null?null:indexes.selectById(state.activeIndexId);
  var building=state==null||state.buildingIndexId==null?null:indexes.selectById(state.buildingIndexId);
  String status=transcript==0?"NO_TRANSCRIPT":"NOT_INDEXED",message=transcript==0?"先生成字幕，再建立问答索引":"字幕已就绪，可以建立问答索引";
  boolean ready=false;
  if(active!=null){
   if(active.transcriptArtifactId!=transcript){status="STALE";message="字幕已更新，请重新建立索引";}
   else if(!active.fingerprint.equals(LearningJson.hash(json.write(List.of(videoId,transcript,properties.snapshot(learning)))))){status="STALE";message="检索配置已变化，请重新建立索引";}
   else if("READY".equals(active.status)){
    try{
     if(qdrant.complete(active,chunks.chunks(active.id),workflow.options(active))){ready=embedding.configured();status=ready?"READY":"AI_NOT_CONFIGURED";message=ready?"可以依据当前视频提问":"检索模型服务尚未配置";}
     else{degrade(active);status="DEGRADED";message="索引不完整，请重新建立";}
    }catch(LearningFailure e){if("INDEX_MISSING".equals(e.code)){degrade(active);status="DEGRADED";}else status="UNAVAILABLE";message=e.getMessage();}
   }else{status="DEGRADED";message="索引需要重新建立";}
  }
  if(building!=null&&!ready){
   if(task!=null&&"FAILED".equals(task.status)){status="FAILED";message=task.errorMessage==null?"索引构建失败，可以恢复任务":task.errorMessage;}
   else if(task!=null&&!Set.of("SUCCEEDED","FAILED").contains(task.status)){status="BUILDING";message="正在建立当前视频索引";}
  }
  var selected=building!=null?building:active;
  int completed=0;if(task!=null)completed=(int)processing.get(owner,task.id).steps().stream().filter(s->s.stage().equals("INDEX_UPSERT")&&s.status().equals("SUCCEEDED")).count();
  var result=new LinkedHashMap<String,Object>();result.put("status",status);result.put("message",message);result.put("ready",ready);result.put("transcriptVersion",transcript);
  result.put("configured",embedding.configured());result.put("indexId",selected==null?null:selected.id);result.put("activeIndexId",active==null?null:active.id);
  result.put("chunkCount",selected==null?0:selected.chunkCount);result.put("batchCount",selected==null?0:selected.batchCount);result.put("completedBatches",completed);
  result.put("task",task==null?null:processing.get(owner,task.id));return result;
 }
 public SearchIndex active(long owner,long video){
  var status=status(owner,video);
  if(!Boolean.TRUE.equals(status.get("ready")))throw error("UNAVAILABLE".equals(status.get("status"))?HttpStatus.SERVICE_UNAVAILABLE:HttpStatus.CONFLICT,"INDEX_NOT_READY",(String)status.get("message"));
  var index=indexes.selectById(((Number)status.get("activeIndexId")).longValue());
  if(index==null||index.ownerId!=owner||index.videoId!=video)throw error(HttpStatus.NOT_FOUND,"INDEX_NOT_FOUND","索引不存在或无法访问");
  return index;
 }
 private void degrade(SearchIndex observed){
  tx.executeWithoutResult(transaction->{
   tasks.lock(observed.taskId);var current=indexes.selectById(observed.id);
   if(current!=null&&"READY".equals(current.status)){current.status="DEGRADED";current.errorCode="INDEX_INCOMPLETE";current.updatedAt=now();indexes.save(current);}
  });
 }
 private ApiException error(HttpStatus status,String code,String message){return new ApiException(status,code,message);}
 private LocalDateTime now(){return LocalDateTime.now(clock);}
}
