package com.coursetrace.retrieval;
import com.coursetrace.learning.*;
import com.coursetrace.processing.*;
import com.coursetrace.outbox.*;
import com.coursetrace.retrieval.SearchModels.*;
import com.coursetrace.retrieval.RetrievalProperties.Options;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

@Service public class IndexWorker {
 private final ProcessingMapper tasks;private final TaskStepMapper steps;private final OutboxMapper outbox;
 private final SearchIndexMapper indexes;private final ChunkMapper chunks;private final IndexWorkflow workflow;
 private final IndexSource source;private final SubtitleChunker chunker;private final EmbeddingBatchService embeddings;
 private final QdrantIndexClient qdrant;private final LearningJson json;private final Clock clock;private final TransactionTemplate tx;
 public IndexWorker(ProcessingMapper tasks,TaskStepMapper steps,OutboxMapper outbox,SearchIndexMapper indexes,ChunkMapper chunks,
  IndexWorkflow workflow,IndexSource source,SubtitleChunker chunker,EmbeddingBatchService embeddings,QdrantIndexClient qdrant,LearningJson json,Clock clock,PlatformTransactionManager manager){
  this.tasks=tasks;this.steps=steps;this.outbox=outbox;this.indexes=indexes;this.chunks=chunks;this.workflow=workflow;this.source=source;
  this.chunker=chunker;this.embeddings=embeddings;this.qdrant=qdrant;this.json=json;this.clock=clock;tx=new TransactionTemplate(manager);
 }
 public void process(ProcessingEvent event){
  var c=tx.execute(status->claim(event));if(c==null)return;
  try{if(c.step.attemptCount>3||c.step.deadlineAt.isBefore(now()))throw LearningFailure.permanent("STAGE_RETRY_BUDGET","此阶段已超时或重试耗尽，请人工恢复");
   var result=execute(c);
   tx.executeWithoutResult(status->{
    var task=tasks.lock(c.task.id);if(task.runNo!=c.task.runNo)return;
    var step=steps.selectById(c.step.id);
    if(!"RUNNING".equals(step.status)||!Objects.equals(step.executionToken,c.step.executionToken))return;
    var data=result.save.get();step.status="SUCCEEDED";step.resultJson=json.write(data);step.finishedAt=now();step.updatedAt=now();steps.saveLearning(step);
    if(result.next!=null)workflow.pending(task,result.next,result.part);
    else{workflow.publish(task,c.index);task.status="SUCCEEDED";task.errorCode=null;task.errorMessage=null;task.finishedAt=now();task.updatedAt=now();tasks.save(task);}
   });
  }catch(LearningFailure failure){fail(c,failure);}
  catch(ProcessingFailures.InvalidEvent e){throw e;}
  catch(RuntimeException failure){fail(c,new LearningFailure("INDEX_STAGE_FAILED","索引阶段暂时无法完成，请稍后恢复",true));}
 }
 private Context claim(ProcessingEvent event){
  var task=tasks.lock(event.taskId());verify(event);
  if(task==null||task.pipelineVersion!=3||task.runNo!=event.runNo()||Set.of("SUCCEEDED","FAILED").contains(task.status))return null;
  var all=steps.current(task.id,task.runNo);
  var step=all.stream().filter(s->s.stage.equals(event.stage())&&s.partId==event.partId()).findFirst().orElseThrow(ProcessingFailures.InvalidEvent::new);
  if(step.dispatchSeq!=event.dispatchSeq()||!task.currentStage.equals(step.stage)||!Set.of("PENDING","RETRY_WAIT").contains(step.status))return null;
  if(all.stream().anyMatch(s->s.id<step.id&&!"SUCCEEDED".equals(s.status)))throw new ProcessingFailures.InvalidEvent();
  var run=workflow.run(task);var index=indexes.selectById(run.indexId());
  if(index==null||!index.taskId.equals(task.id)||!index.videoId.equals(task.videoId))throw new ProcessingFailures.InvalidEvent();
  if(step.partId>index.batchCount&&!"CHUNK_BUILD".equals(step.stage))throw new ProcessingFailures.InvalidEvent();
  step.status="RUNNING";step.executionToken=UUID.randomUUID().toString();step.attemptCount++;step.startedAt=now();step.updatedAt=now();step.errorCode=null;step.errorMessage=null;steps.saveLearning(step);
  task.status="RUNNING";task.startedAt=now();task.updatedAt=now();task.errorCode=null;task.errorMessage=null;tasks.save(task);
  return new Context(task,step,index,run.config());
 }
 private Result execute(Context c){
  return switch(c.step.stage){
   case "CHUNK_BUILD" -> {
    var drafts=chunker.split(source.read(c.index.ownerId,c.index.videoId,c.index.transcriptArtifactId),c.config);
    yield new Result(()->{
     var current=chunks.chunks(c.index.id);
     if(current.isEmpty())for(int n=0;n<drafts.size();n++){
      var draft=drafts.get(n);var chunk=new Chunk();chunk.indexId=c.index.id;chunk.ordinal=n;chunk.text=draft.text();chunk.contentHash=LearningJson.hash(chunk.text);
      chunk.pointId=UUID.nameUUIDFromBytes(("coursetrace:"+c.index.id+":"+n).getBytes(StandardCharsets.UTF_8)).toString();
      chunk.beginMs=draft.beginMs();chunk.endMs=draft.endMs();chunks.insert(chunk);
      int ordinal=0;for(var relation:draft.sources()){var row=new ChunkSource();row.chunkId=chunk.id;row.segmentId=relation.segmentId();row.ordinal=ordinal++;row.startOffset=relation.startOffset();row.endOffset=relation.endOffset();chunks.source(row);}
     }
     else if(current.size()!=drafts.size()||java.util.stream.IntStream.range(0,current.size()).anyMatch(n->!current.get(n).text.equals(drafts.get(n).text())))
      throw LearningFailure.permanent("CHUNK_CHECKPOINT_INVALID","字幕切块检查点不匹配");
     c.index.chunkCount=drafts.size();c.index.batchCount=(drafts.size()+c.config.batchSize()-1)/c.config.batchSize();c.index.updatedAt=now();indexes.save(c.index);
     return Map.of("chunkCount",c.index.chunkCount,"batchCount",c.index.batchCount);
    },"EMBED_BATCH",1);
   }
   case "EMBED_BATCH" -> {
    var batch=batch(c);var artifact=embeddings.generate(c.task,c.index,batch,c.config);
    yield new Result(()->Map.of("batch",c.step.partId,"artifactId",artifact.id,"chunkCount",batch.size()),"INDEX_UPSERT",c.step.partId);
   }
   case "INDEX_UPSERT" -> {
    var batch=batch(c);var artifact=embeddings.cached(c.index,batch,c.config);
    if(c.step.partId==1)qdrant.ensure(c.config);
    qdrant.upsert(c.index,batch,embeddings.vectors(artifact,batch.size(),c.config.dimensions()),c.config);
    yield new Result(()->Map.of("batch",c.step.partId,"chunkCount",batch.size()),c.step.partId<c.index.batchCount?"EMBED_BATCH":"INDEX_PUBLISH",c.step.partId<c.index.batchCount?c.step.partId+1:0);
   }
   case "INDEX_PUBLISH" -> {
    if(source.current(c.index.ownerId,c.index.videoId)!=c.index.transcriptArtifactId)throw LearningFailure.permanent("TRANSCRIPT_CHANGED","字幕已更新，请重新建立索引");
    if(!qdrant.complete(c.index,chunks.chunks(c.index.id),c.config))throw new LearningFailure("INDEX_INCOMPLETE","向量索引不完整，请恢复或重新建立",true);
    yield new Result(()->{
     if(source.current(c.index.ownerId,c.index.videoId)!=c.index.transcriptArtifactId)throw LearningFailure.permanent("TRANSCRIPT_CHANGED","字幕已更新，请重新建立索引");
     return Map.of("chunkCount",c.index.chunkCount,"batchCount",c.index.batchCount);
    },null,0);
   }
   default -> throw new ProcessingFailures.InvalidEvent();
  };
 }
 private List<Chunk> batch(Context c){
  var all=chunks.chunks(c.index.id);int start=(c.step.partId-1)*c.config.batchSize();
  if(c.step.partId<1||start>=all.size()||all.size()!=c.index.chunkCount)throw LearningFailure.permanent("INVALID_INDEX_BATCH","向量批次不正确");
  return all.subList(start,Math.min(start+c.config.batchSize(),all.size()));
 }
 private void fail(Context c,LearningFailure failure){
  tx.executeWithoutResult(status->{
   var task=tasks.lock(c.task.id);if(task.runNo!=c.task.runNo)return;var step=steps.selectById(c.step.id);
   if(!"RUNNING".equals(step.status)||!Objects.equals(step.executionToken,c.step.executionToken))return;
   step.status="RETRY_WAIT";step.failureCount++;step.errorCode=failure.code;step.errorMessage=failure.getMessage();step.updatedAt=now();steps.saveLearning(step);
   task.status="RETRY_WAIT";task.errorCode=failure.code;task.errorMessage=failure.getMessage();task.updatedAt=now();tasks.save(task);
  });
  if(failure.retryable)throw new ProcessingFailures.Retryable();throw new ProcessingFailures.Permanent();
 }
 public void deadLetter(ProcessingEvent event){
  tx.executeWithoutResult(status->{
   var task=tasks.lock(event.taskId());try{verify(event);}catch(ProcessingFailures.InvalidEvent e){return;}
   if(task==null||task.pipelineVersion!=3||task.runNo!=event.runNo()||!"RETRY_WAIT".equals(task.status))return;
   var found=steps.current(task.id,task.runNo).stream().filter(s->s.stage.equals(event.stage())&&s.partId==event.partId()&&s.dispatchSeq==event.dispatchSeq()&&"RETRY_WAIT".equals(s.status)).findFirst();
   if(found.isEmpty())return;var step=found.get();step.status="FAILED";step.finishedAt=now();step.updatedAt=now();steps.saveLearning(step);
   task.status="FAILED";task.errorCode=step.errorCode;task.errorMessage=step.errorMessage;task.finishedAt=now();task.updatedAt=now();tasks.save(task);workflow.failed(task,step.errorCode);
  });
 }
 private void verify(ProcessingEvent event){
  var bound=outbox.selectById(event.eventId());
  if(bound==null||bound.taskId!=event.taskId()||bound.pipelineVersion!=3||bound.runNo!=event.runNo()||!bound.stage.equals(event.stage())||bound.partId!=event.partId()||bound.dispatchSeq!=event.dispatchSeq())throw new ProcessingFailures.InvalidEvent();
 }
 private LocalDateTime now(){return LocalDateTime.now(clock);}
 private record Context(ProcessingTask task,TaskStep step,SearchIndex index,Options config){}
 private record Result(Supplier<Map<String,Object>> save,String next,int part){}
}
