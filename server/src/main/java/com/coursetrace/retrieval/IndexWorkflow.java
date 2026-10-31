package com.coursetrace.retrieval;
import com.coursetrace.learning.*;
import com.coursetrace.processing.*;
import com.coursetrace.outbox.*;
import com.coursetrace.retrieval.SearchModels.*;
import com.coursetrace.retrieval.RetrievalProperties.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service public class IndexWorkflow {
 private final ProcessingMapper tasks;private final TaskStepMapper steps;private final OutboxMapper outbox;
 private final SearchIndexMapper indexes;private final SearchStateMapper states;private final LearningJson json;
 private final ObjectMapper mapper;private final Clock clock;
 public IndexWorkflow(ProcessingMapper tasks,TaskStepMapper steps,OutboxMapper outbox,SearchIndexMapper indexes,SearchStateMapper states,LearningJson json,ObjectMapper mapper,Clock clock){
  this.tasks=tasks;this.steps=steps;this.outbox=outbox;this.indexes=indexes;this.states=states;this.json=json;this.mapper=mapper;this.clock=clock;
 }
 public Options options(SearchIndex index){try{return mapper.readValue(index.configJson,Options.class);}catch(Exception e){throw LearningFailure.permanent("INVALID_TASK_CONFIG","索引配置无法读取");}}
 public IndexRun run(ProcessingTask task){try{return mapper.readValue(task.parametersJson,IndexRun.class);}catch(Exception e){throw LearningFailure.permanent("INVALID_TASK_CONFIG","索引任务配置无法读取");}}
 public void startRun(ProcessingTask task){
  var run=run(task);var index=indexes.selectById(run.indexId());
  if(index==null||!index.videoId.equals(task.videoId)||!index.taskId.equals(task.id))throw LearningFailure.permanent("INDEX_BINDING_INVALID","索引任务关联不正确");
  index.status="BUILDING";index.errorCode=null;index.updatedAt=now();indexes.save(index);
  var state=states.selectById(task.videoId);state.buildingIndexId=index.id;state.updatedAt=now();states.save(state);
  // Revalidate and upsert all batches on manual recovery; paid results are reused by fingerprint.
  pending(task,"CHUNK_BUILD",0);
 }
 public void pending(ProcessingTask task,String stage,int part){
  var step=new TaskStep();step.taskId=task.id;step.runNo=task.runNo;step.stage=stage;step.partId=part;
  step.status="PENDING";step.maxAttempts=3;step.createdAt=now();step.updatedAt=now();step.deadlineAt=now().plusMinutes(10);steps.insert(step);
  task.currentStage=stage;task.status="PENDING";task.startedAt=null;task.finishedAt=null;task.errorCode=null;task.errorMessage=null;task.updatedAt=now();tasks.save(task);
  var event=new OutboxEvent();event.eventId=UUID.randomUUID().toString();event.taskId=task.id;event.runNo=task.runNo;event.pipelineVersion=3;
  event.stage=stage;event.partId=part;event.dispatchSeq=0;event.topic=ProcessingEvent.TOPIC;event.messageKey=task.id.toString();
  event.status="PENDING";event.createdAt=now();event.nextAttemptAt=now();
  event.payloadJson=json.write(new ProcessingEvent(2,event.eventId,task.id,3,task.runNo,stage,part,0));outbox.insert(event);
 }
 public void publish(ProcessingTask task,SearchIndex index){
  var state=states.selectById(task.videoId);
  if(state==null||!Objects.equals(state.buildingIndexId,index.id))throw LearningFailure.permanent("INDEX_CHANGED","构建中的索引已变化");
  index.status="READY";index.errorCode=null;index.updatedAt=now();indexes.save(index);
  state.activeIndexId=index.id;state.buildingIndexId=null;state.updatedAt=now();states.save(state);
 }
 public void failed(ProcessingTask task,String code){
  var index=indexes.selectById(run(task).indexId());
  if(index!=null){index.status="FAILED";index.errorCode=code;index.updatedAt=now();indexes.save(index);}
 }
 private LocalDateTime now(){return LocalDateTime.now(clock);}
}
