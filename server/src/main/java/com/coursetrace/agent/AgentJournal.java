package com.coursetrace.agent;
import com.coursetrace.agent.AgentModels.*;
import com.coursetrace.retrieval.*;
import com.coursetrace.retrieval.SearchModels.*;
import com.coursetrace.learning.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
@Service public class AgentJournal {
 private final AgentRunMapper runs;private final AgentStepMapper steps;private final ChatTurnMapper turns;
 private final IndexSource source;private final AgentProperties properties;private final LearningJson json;private final Clock clock;private final TransactionTemplate tx;
 public AgentJournal(AgentRunMapper runs,AgentStepMapper steps,ChatTurnMapper turns,AgentProperties properties,LearningJson json,Clock clock,PlatformTransactionManager manager,IndexSource source){
  this.source=source;this.runs=runs;this.steps=steps;this.turns=turns;this.properties=properties;this.json=json;this.clock=clock;tx=new TransactionTemplate(manager);
 }
 public record Config(AgentProperties.Budget budget,RetrievalProperties.Options search){}
 public Run create(ChatTurn turn,SearchIndex index,RetrievalProperties.Options options){
  var budget=properties.snapshot();turn.deadlineAt=turn.createdAt.plusSeconds(budget.deadlineSeconds());
  var r=new Run();r.turnId=turn.id;r.ownerId=turn.ownerId;r.courseId=index.courseId;r.videoId=turn.videoId;r.indexId=index.id;r.transcriptArtifactId=turn.transcriptArtifactId;
  r.configJson=json.write(new Config(budget,options));r.status="RUNNING";r.phase="SEARCH";r.executionToken=turn.executionToken;r.createdAt=now();r.updatedAt=now();r.deadlineAt=turn.deadlineAt;runs.insert(r);turns.save(turn);return r;
 }
 public Run run(long turn){return runs.byTurn(turn);}
 public Step begin(Run r,ChatTurn t,Config config,String kind,String name,String callId,String args,boolean actual,boolean finalCall){
  return tx.execute(s->{
   active(r,t);var b=config.budget();
   if(kind.equals("MODEL")){
    if(t.chatRequests>=b.maxChats()||(!finalCall&&r.rounds>=b.maxRounds())||t.outputTokens>=b.maxOutputTokens())throw limit("ROUND_LIMIT");
    t.chatRequests++;if(!finalCall)r.rounds++;r.phase=finalCall?"FINAL":"DECIDE";
   }else{
    if(r.toolCalls>=b.maxTools())throw limit("TOOL_LIMIT");
    if(actual&&"searchSegments".equals(name)&&r.searchCalls>=b.maxSearches())throw limit("SEARCH_LIMIT");
    if(actual&&"readSurroundingContext".equals(name)&&r.contextCalls>=b.maxReads())throw limit("CONTEXT_LIMIT");r.toolCalls++;
    if(actual&&"searchSegments".equals(name)){if(r.searchCalls>=b.maxSearches())throw limit("SEARCH_LIMIT");r.searchCalls++;t.embeddingRequests++;}
    if(actual&&"readSurroundingContext".equals(name)){if(r.contextCalls>=b.maxReads())throw limit("CONTEXT_LIMIT");r.contextCalls++;}
    r.phase="readSurroundingContext".equals(name)?"CONTEXT":"SEARCH";
   }
   var step=new Step();step.runId=r.id;step.seq=steps.steps(r.id).size()+1;step.kind=kind;step.phase=r.phase;step.name=name;step.toolCallId=callId;step.argsJson=args;step.status="STARTED";step.createdAt=now();step.updatedAt=now();steps.insert(step);
   save(r,t);return step;
  });
 }
 public void complete(Run r,ChatTurn t,Step step,String result,boolean cached,int sourceCount,long input,long output,long elapsed,String error){
  tx.executeWithoutResult(s->{
   active(r,t);step.status=error==null?"SUCCEEDED":"REJECTED";step.errorCode=error;step.resultJson=result;step.cached=cached;step.sourceCount=sourceCount;step.elapsedMs=elapsed;step.inputTokens=input;step.outputTokens=output;step.updatedAt=now();steps.updateById(step);
   t.inputTokens+=input;t.outputTokens+=output;save(r,t);
  });
 }
 public void finish(Run r,ChatTurn t,String status,String reason,boolean limited,long started){
  tx.executeWithoutResult(s->{active(r,t);r.status=status;r.stopReason=reason;r.limited=limited;r.phase="FINISHED";t.status=status;t.elapsedMs=(System.nanoTime()-started)/1000000;save(r,t);});
 }
 public void failure(Run r,ChatTurn t,LearningFailure e,long started){
  tx.executeWithoutResult(s->{
   var current=turns.lock(t.id);var latest=runs.lock(r.id);
   if(current==null||latest==null||!"RUNNING".equals(current.status)||!"RUNNING".equals(latest.status)||!Objects.equals(current.executionToken,t.executionToken)||!Objects.equals(latest.executionToken,r.executionToken))return;
   String status=e.uncertain||e.code.equals("EMBEDDING_UNAVAILABLE")?"UNKNOWN":"FAILED";
   if(!current.deadlineAt.isAfter(now()))status="UNKNOWN";
   r.status=status;r.phase="FINISHED";r.stopReason=e.code.equals("INVALID_ANSWER")?"INVALID_ANSWER":e.code;
   t.status=status;t.errorCode=e.code;t.errorMessage=e.getMessage();t.elapsedMs=(System.nanoTime()-started)/1000000;
   for(var step:steps.steps(r.id))if(step.status.equals("STARTED")){step.status=status;step.errorCode=e.code;step.updatedAt=now();steps.updateById(step);}
   save(r,t);
  });
 }
 public void expire(long owner){runs.expire(owner,now());steps.expire(owner,now());}
 public List<Run> summaries(List<Long> ids){return ids.isEmpty()?List.of():runs.forTurns(ids);}
 public Map<String,Object> summary(Run r){
  var m=new LinkedHashMap<String,Object>();m.put("id",r.id);m.put("status",r.status);m.put("phase",r.phase);m.put("stopReason",r.stopReason);m.put("limited",r.limited);
  m.put("rounds",r.rounds);m.put("toolCalls",r.toolCalls);m.put("searchCalls",r.searchCalls);m.put("contextCalls",r.contextCalls);return m;
 }
 public Map<String,Object> detail(long turn){
  var r=runs.byTurn(turn);if(r==null)return Map.of();
  var canonical=new HashMap<Long,Segment>();for(var segment:source.read(r.ownerId,r.videoId,r.transcriptArtifactId))canonical.put(segment.id,segment);
  var m=new LinkedHashMap<>(summary(r));m.put("config",json.read(r.configJson));m.put("steps",steps.steps(r.id).stream().map(step->{
   if(step.kind.equals("TOOL")&&step.resultJson!=null){
    try{
     for(var item:json.read(step.resultJson).path("sources")){
      if(!item.path("segmentId").isIntegralNumber()||!item.path("startOffset").isInt()||!item.path("endOffset").isInt())throw new IllegalArgumentException();
      var segment=canonical.get(item.path("segmentId").asLong());int start=item.path("startOffset").asInt(),end=item.path("endOffset").asInt();
      RetrievalService.check(segment,start,end);
      if(!segment.text.substring(start,end).equals(item.path("text").asText())||segment.beginMs!=item.path("beginMs").asLong()||segment.endMs!=item.path("endMs").asLong())throw new IllegalArgumentException();
     }
    }catch(RuntimeException invalid){throw new com.coursetrace.common.ApiException(org.springframework.http.HttpStatus.CONFLICT,"INVALID_SAVED_AGENT_SOURCE","已保存处理过程的来源无法验证");}
   }
   var v=new LinkedHashMap<String,Object>();v.put("seq",step.seq);v.put("kind",step.kind);v.put("phase",step.phase);v.put("name",step.name);v.put("toolCallId",step.toolCallId);
   v.put("arguments",step.argsJson==null?null:json.read(step.argsJson));v.put("result",step.resultJson==null?null:json.read(step.resultJson));v.put("status",step.status);v.put("cached",step.cached);v.put("sourceCount",step.sourceCount);v.put("elapsedMs",step.elapsedMs);
   v.put("inputTokens",step.inputTokens);v.put("outputTokens",step.outputTokens);v.put("errorCode",step.errorCode);return v;
  }).toList());return m;
 }
 public int remaining(Run r){long s=Duration.between(now(),r.deadlineAt).toSeconds();return (int)Math.max(0,s);}
 private void active(Run r,ChatTurn t){
  var current=turns.lock(t.id);var latest=runs.lock(r.id);
  if(current==null||latest==null||!"RUNNING".equals(current.status)||!"RUNNING".equals(latest.status)||!Objects.equals(current.executionToken,t.executionToken)||!Objects.equals(latest.executionToken,r.executionToken)||!current.deadlineAt.isAfter(now()))
   throw LearningFailure.permanent("EXECUTION_EXPIRED","本次执行已经结束或超过期限");
 }
 private void save(Run r,ChatTurn t){r.updatedAt=now();t.updatedAt=now();runs.updateById(r);if(turns.save(t)!=1)throw LearningFailure.permanent("EXECUTION_CHANGED","本次问答已结束");}
 private LearningFailure limit(String code){return LearningFailure.permanent(code,"本次深入问答已达到处理上限");}
 private LocalDateTime now(){return LocalDateTime.now(clock);}
}
