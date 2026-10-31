package com.coursetrace.retrieval;
import com.coursetrace.common.ApiException;
import com.coursetrace.agent.*;
import com.coursetrace.learning.*;
import com.coursetrace.video.VideoService;
import com.coursetrace.retrieval.SearchModels.*;
import com.coursetrace.retrieval.RetrievalService.Evidence;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service public class CourseQaService {
 private final ChatTurnMapper turns;private final SearchIndexMapper indexes;private final SearchIndexService search;
 private final IndexWorkflow workflow;private final RetrievalService retrieval;private final QwenEmbeddingClient embeddings;
 private final QaModelClient model;private final CitationValidator citations;private final VideoService videos;
 private final LearningJson json;private final ObjectMapper mapper;private final Clock clock;private final TransactionTemplate tx;
 private final Semaphore concurrent=new Semaphore(2);
 private final com.coursetrace.limit.RedisAdmission admission;
 private final AgentLoop agent;private final AgentJournal agentJournal;
 public CourseQaService(ChatTurnMapper turns,SearchIndexMapper indexes,SearchIndexService search,IndexWorkflow workflow,RetrievalService retrieval,QwenEmbeddingClient embeddings,
  QaModelClient model,CitationValidator citations,VideoService videos,LearningJson json,ObjectMapper mapper,Clock clock,PlatformTransactionManager manager,AgentLoop agent,AgentJournal agentJournal,com.coursetrace.limit.RedisAdmission admission){
  this.admission=admission;
  this.turns=turns;this.indexes=indexes;this.search=search;this.workflow=workflow;this.retrieval=retrieval;this.embeddings=embeddings;this.model=model;this.citations=citations;
  this.videos=videos;this.json=json;this.mapper=mapper;this.clock=clock;tx=new TransactionTemplate(manager);this.agent=agent;this.agentJournal=agentJournal;
 }
 public Map<String,Object> ask(long owner,long videoId,String requestId,String question){
  return ask(owner,videoId,requestId,question,"RAG");
 }
 // Both modes share this admission path, semaphore, owner lock and rate limit.
 public Map<String,Object> ask(long owner,long videoId,String requestId,String question,String mode){
  if(mode==null)mode="RAG";
  if(!Set.of("RAG","AGENT").contains(mode))throw error(HttpStatus.BAD_REQUEST,"INVALID_QA_MODE","问答模式无效");
  final String requestedMode=mode;
  videos.owned(owner,videoId);
  if(question==null||question.isBlank()||question.codePointCount(0,question.length())>500||question.length()>1000)
   throw error(HttpStatus.BAD_REQUEST,"INVALID_QUESTION","请输入 1～500 字的问题");
  String normalized=question.strip();
  expire(owner);var existing=turns.request(owner,videoId,requestId);
  if(existing!=null){sameQuestion(existing,normalized,requestedMode);return view(existing);}
  if(!concurrent.tryAcquire())throw error(HttpStatus.TOO_MANY_REQUESTS,"QA_BUSY","问答服务正在处理其他请求，请稍后再试");
  try{
   var index=search.active(owner,videoId);
   var admitted=tx.execute(transaction->{
    turns.lockOwner(owner);turns.expire(owner,now());agentJournal.expire(owner);var duplicate=turns.request(owner,videoId,requestId);
    if(duplicate!=null){sameQuestion(duplicate,normalized,requestedMode);return new Admission(duplicate,false);}
    if(turns.active(owner)>0)throw error(HttpStatus.CONFLICT,"QUESTION_IN_PROGRESS","上一条问答还在处理中，请等待结果");
    if(turns.recentCount(owner,now().minusMinutes(1))>=6)throw error(HttpStatus.TOO_MANY_REQUESTS,"QA_RATE_LIMIT","每分钟最多提问六次，请稍后再试");
    admission.admit(owner,"qa",videoId+":"+requestId,normalized+"\n"+requestedMode);
    var turn=new ChatTurn();turn.ownerId=owner;turn.videoId=videoId;turn.indexId=index.id;turn.transcriptArtifactId=index.transcriptArtifactId;
    turn.mode=requestedMode;turn.requestId=requestId;turn.question=normalized;turn.status="RUNNING";turn.executionToken=UUID.randomUUID().toString();turn.createdAt=now();turn.updatedAt=now();turn.deadlineAt=now().plusSeconds(requestedMode.equals("AGENT")?120:90);turns.insert(turn);
    if(requestedMode.equals("AGENT"))agentJournal.create(turn,index,workflow.options(index));
    return new Admission(turn,true);
   });
   if(!admitted.created)return view(admitted.turn);
   var turn=admitted.turn;long started=System.nanoTime();
   if(requestedMode.equals("AGENT")){agent.execute(turn,index,historyContext(turn));return view(turns.selectById(turn.id));}
   try{
    var config=workflow.options(index);var history=historyContext(turn);
    String query=turn.question;
    if(!history.isEmpty())query+="\n最近一轮问题："+history.getLast().get("question");
    markCall(turn,false);
    var vector=embeddings.embed(config,List.of(query),Math.min(20,remaining(turn)));turn.inputTokens+=vector.inputTokens();
    var evidence=retrieval.retrieve(index,vector.vectors().getFirst(),config);
    turn.contextJson=json.write(evidence);checkpoint(turn);
    if(evidence.isEmpty()){turn.responseJson="{\"status\":\"INSUFFICIENT_EVIDENCE\",\"paragraphs\":[]}";finish(turn,citations.insufficient(),started);}
    else{
     markCall(turn,true);
     var response=model.answer(config,turn.question,evidence,history,Math.min(60,remaining(turn)));
     turn.responseJson=response.text();turn.inputTokens+=response.inputTokens();turn.outputTokens+=response.outputTokens();checkpoint(turn);
     var answer=citations.validate(turn.responseJson,index,evidence);finish(turn,answer,started);
    }
   }catch(LearningFailure failure){fail(turn,failure,started);}
   catch(RuntimeException failure){fail(turn,new LearningFailure("ANSWER_INTERRUPTED","问答结果未能保存，请查看状态后再决定是否重新提问",false,true),started);}
   return view(turns.selectById(turn.id));
  }finally{concurrent.release();}
 }
 private List<Map<String,String>> historyContext(ChatTurn turn){
  var recent=new ArrayList<>(turns.context(turn.ownerId,turn.videoId,turn.indexId,turn.id));Collections.reverse(recent);
  var result=new ArrayList<Map<String,String>>();int remaining=2000;
  for(var previous:recent){
   try{
    var answer=json.read(previous.answerJson);var text=new StringBuilder();
    for(var paragraph:answer.path("paragraphs"))text.append(paragraph.path("text").asText()).append('\n');
    String q=previous.question,a=text.toString();if(q.length()+a.length()>remaining){a=cut(a,Math.max(0,remaining-q.length()));}
    if(q.length()+a.length()>remaining)continue;
    result.add(Map.of("question",q,"answer",a));remaining-=q.length()+a.length();
   }catch(RuntimeException ignored){/* A damaged historic turn is never used as evidence. */}
  }
  return result;
 }
 private String cut(String text,int length){int end=Math.min(text.length(),length);if(end>0&&end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;return text.substring(0,end);}
 private void markCall(ChatTurn turn,boolean chat){
  tx.executeWithoutResult(transaction->{
   var current=turns.lock(turn.id);checkActive(current,turn);
   if(chat){if(current.chatRequests>=1)throw LearningFailure.permanent("QA_BUDGET","本轮回答预算已使用");turn.chatRequests=1;}
   else{if(current.embeddingRequests>=1)throw LearningFailure.permanent("QA_BUDGET","本轮查询预算已使用");turn.embeddingRequests=1;}
   turn.updatedAt=now();if(turns.save(turn)!=1)throw LearningFailure.permanent("QUESTION_CHANGED","本轮问答已结束");
  });
 }
 private void checkpoint(ChatTurn turn){
  tx.executeWithoutResult(transaction->{checkActive(turns.lock(turn.id),turn);turn.updatedAt=now();if(turns.save(turn)!=1)throw LearningFailure.permanent("QUESTION_CHANGED","本轮问答已结束");});
 }
 private void finish(ChatTurn turn,CitationValidator.Answer answer,long started){
  tx.executeWithoutResult(transaction->{
   checkActive(turns.lock(turn.id),turn);turn.answerJson=json.write(answer);turn.status=answer.status().equals("ANSWERED")?"SUCCEEDED":"INSUFFICIENT_EVIDENCE";
   turn.elapsedMs=(System.nanoTime()-started)/1000000;turn.updatedAt=now();turns.save(turn);
  });
 }
 private void fail(ChatTurn turn,LearningFailure failure,long started){
  tx.executeWithoutResult(transaction->{
   var current=turns.lock(turn.id);if(current==null||!"RUNNING".equals(current.status)||!Objects.equals(current.executionToken,turn.executionToken))return;
   turn.status=failure.uncertain||(failure.retryable&&(turn.embeddingRequests>0||turn.chatRequests>0))?"UNKNOWN":"FAILED";
   turn.errorCode=failure.code;turn.errorMessage=failure.getMessage();turn.updatedAt=now();turn.elapsedMs=(System.nanoTime()-started)/1000000;turns.save(turn);
  });
 }
 private void checkActive(ChatTurn current,ChatTurn expected){
  if(current==null||!"RUNNING".equals(current.status)||!Objects.equals(current.executionToken,expected.executionToken)||!current.deadlineAt.isAfter(now()))
   throw LearningFailure.permanent("QUESTION_CHANGED","本轮问答已结束或超时，请查看历史状态");
 }
 private int remaining(ChatTurn turn){long seconds=Duration.between(now(),turn.deadlineAt).toSeconds();if(seconds<1)throw LearningFailure.permanent("QA_TIMEOUT","本轮问答已超时");return (int)seconds;}
 private void sameQuestion(ChatTurn turn,String question,String mode){if(!turn.question.equals(question)||!Objects.equals(turn.mode,mode))throw error(HttpStatus.CONFLICT,"REQUEST_ID_REUSED","请求编号已用于其他问题，请刷新后重新提问");}
 private void expire(long owner){tx.executeWithoutResult(transaction->{turns.lockOwner(owner);turns.expire(owner,now());agentJournal.expire(owner);});}
 public Map<String,Object> get(long owner,long video,long id){
  videos.owned(owner,video);expire(owner);var turn=turns.selectById(id);
  if(turn==null||turn.ownerId!=owner||turn.videoId!=video)throw error(HttpStatus.NOT_FOUND,"QUESTION_NOT_FOUND","问答记录不存在或无法访问");return view(turn);
 }
 // Reconciliation reads an existing request; it never submits a model call.
 public Map<String,Object> request(long owner,long video,String requestId){
  videos.owned(owner,video);expire(owner);var turn=turns.request(owner,video,requestId);
  if(turn==null)throw error(HttpStatus.NOT_FOUND,"QUESTION_NOT_FOUND","暂未查到该请求，请稍后再次确认");
  return view(turn);
 }
 public Map<String,Object> history(long owner,long video,int page,int size){
  videos.owned(owner,video);expire(owner);
  return Map.of("items",historyViews(turns.history(owner,video,size,(page-1)*size)),"total",turns.count(owner,video),"page",page,"size",size);
 }
 public Map<String,Object> agentRun(long owner,long video,long id){
  get(owner,video,id);
  var detail=agentJournal.detail(id);
  if(detail.isEmpty())throw error(HttpStatus.NOT_FOUND,"AGENT_RUN_NOT_FOUND","该问答没有 Agent 执行记录");
  return detail;
 }
 private List<Map<String,Object>> historyViews(List<ChatTurn> records){
  var summaries=new HashMap<Long,AgentModels.Run>();
  for(var run:agentJournal.summaries(records.stream().filter(t->"AGENT".equals(t.mode)).map(t->t.id).toList()))summaries.put(run.turnId,run);
  return records.stream().map(t->view(t,summaries.get(t.id))).toList();
 }
 private Map<String,Object> view(ChatTurn turn){return view(turn,"AGENT".equals(turn.mode)?agentJournal.run(turn.id):null);}
 private Map<String,Object> view(ChatTurn turn,AgentModels.Run run){
  var result=new LinkedHashMap<String,Object>();CitationValidator.Answer answer=null;String invalidMessage=null;
  if(Set.of("SUCCEEDED","INSUFFICIENT_EVIDENCE").contains(turn.status))try{
   var index=indexes.selectById(turn.indexId);
   if(index==null||!index.ownerId.equals(turn.ownerId)||!index.videoId.equals(turn.videoId)||!index.transcriptArtifactId.equals(turn.transcriptArtifactId))throw new IllegalStateException();
   List<Evidence> evidence=turn.contextJson==null?List.of():mapper.readValue(turn.contextJson,new TypeReference<List<Evidence>>(){});
   answer=citations.validate(turn.responseJson,index,evidence);
  }catch(Exception invalid){invalidMessage="已保存回答的来源暂时无法验证，请重新提问";}
  result.put("mode",turn.mode);result.put("agentRun",run==null?null:agentJournal.summary(run));
  result.put("id",turn.id);result.put("videoId",turn.videoId);result.put("indexId",turn.indexId);result.put("transcriptVersion",turn.transcriptArtifactId);
  result.put("requestId",turn.requestId);result.put("question",turn.question);result.put("status",invalidMessage==null?turn.status:"FAILED");result.put("answer",answer);
  result.put("errorCode",invalidMessage==null?turn.errorCode:"INVALID_SAVED_ANSWER");result.put("message",invalidMessage==null?turn.errorMessage:invalidMessage);
  result.put("createdAt",turn.createdAt.toInstant(ZoneOffset.UTC));result.put("elapsedMs",turn.elapsedMs);
  result.put("usage",Map.of("inputTokens",turn.inputTokens,"outputTokens",turn.outputTokens));return result;
 }
 private LocalDateTime now(){return LocalDateTime.now(clock);}
 private ApiException error(HttpStatus status,String code,String message){return new ApiException(status,code,message);}
 private record Admission(ChatTurn turn,boolean created){}
}
