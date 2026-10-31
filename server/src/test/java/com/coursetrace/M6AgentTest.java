package com.coursetrace;
import com.coursetrace.retrieval.*;
import com.coursetrace.agent.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import com.coursetrace.retrieval.SearchModels.*;
import com.coursetrace.learning.*;
import com.coursetrace.processing.*;
import com.coursetrace.outbox.*;
import com.coursetrace.video.*;
import com.coursetrace.course.*;
import com.coursetrace.auth.*;
import com.coursetrace.common.ApiException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:coursetrace_m6;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "coursetrace.learning.api-key=unit-test-placeholder","coursetrace.retrieval.target-chars=100",
 "coursetrace.retrieval.max-chars=160","coursetrace.retrieval.overlap-chars=20","coursetrace.retrieval.batch-size=2"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class M6AgentTest {
 @Autowired SearchIndexService search;@Autowired CourseQaService qa;@Autowired ProcessingService processing;@Autowired ProcessingWorker worker;
 @Autowired ProcessingMapper tasks;@Autowired TaskStepMapper steps;@MockitoSpyBean OutboxMapper outbox;
 @Autowired SearchIndexMapper indexes;@MockitoSpyBean SearchStateMapper states;@Autowired ChunkMapper chunks;@Autowired ChatTurnMapper turns;
 @Autowired ArtifactMapper artifacts;@Autowired SegmentMapper segments;@Autowired CourseMapper courses;@Autowired CourseVideoMapper videos;
 @Autowired AppUserMapper users;@Autowired PasswordEncoder passwords;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
 @MockitoBean QwenEmbeddingClient embedding;@MockitoBean QaModelClient model;@MockitoBean QdrantIndexClient qdrant;@MockitoBean MediaStorage storage;
 @MockitoBean AgentModelClient agentModel;@Autowired AgentRunMapper agentRuns;@Autowired AgentStepMapper agentSteps;@Autowired AgentToolService toolService;@Autowired ContextReadService contextRead;@Autowired AgentProperties agentProperties;
 long owner,course,video,transcript;MockHttpSession alice,bob;
 Map<String,byte[]> objects=new ConcurrentHashMap<>();Map<String,QdrantIndexClient.Hit> points=new ConcurrentHashMap<>();
 @BeforeEach void setup() throws Exception {
  for(String table:List.of("agent_step","agent_run","chat_turn","subtitle_chunk_segment","subtitle_chunk","video_search_state","video_search_index","course_note","course_segment","asr_job","processing_artifact","outbox_event","task_step","processing_task","course_video","course"))jdbc.update("DELETE FROM "+table);
  owner=user("m5alice");user("m5bob");alice=login("m5alice");bob=login("m5bob");
  var c=new Course();c.setOwnerId(owner);c.setTitle("检索课程");c.setDescription("");c.setCreatedAt(now());c.setUpdatedAt(now());courses.insert(c);course=c.getId();
  var v=new CourseVideo();v.setCourseId(course);v.setTitle("二分查找");v.setOriginalFilename("lesson.mp4");v.setBucketName("test-videos");v.setObjectKey("lesson.mp4");v.setSizeBytes(100L);v.setContentType("video/mp4");v.setDurationMs(20000L);v.setWidth(640);v.setHeight(360);v.setVideoCodec("h264");v.setAudioCodec("aac");v.setCreatedAt(now());videos.insert(v);video=v.getId();
  var a=new Artifact();a.videoId=video;a.kind="ASR_JSON";a.fingerprint=LearningJson.hash("transcript"+video);a.state="READY";a.createdAt=now();a.updatedAt=now();artifacts.insert(a);transcript=a.id;
  var t=new ProcessingTask();t.videoId=video;t.pipelineVersion=2;t.runNo=1;t.status="SUCCEEDED";t.currentStage="NOTE_BUILD";t.createdAt=now();t.updatedAt=now();tasks.insert(t);
  var step=new TaskStep();step.taskId=t.id;step.runNo=1;step.stage="TRANSCRIPT_BUILD";step.status="SUCCEEDED";step.maxAttempts=3;step.resultJson=json.writeValueAsString(Map.of("transcriptArtifactId",transcript,"segmentCount",8,"windowCount",1,"durationMs",20000));step.createdAt=now();step.updatedAt=now();steps.insert(step);
  for(int n=0;n<8;n++){var s=new Segment();s.videoId=video;s.transcriptArtifactId=transcript;s.ordinal=n;s.beginMs=n*2000+100;s.endMs=n*2000+1800;s.text="第"+n+"节：本节讲解有序数组上的二分查找。先比较中间元素，再缩小搜索范围，每轮最多保留一半元素。数组有序才能安全排除不可能包含目标的一侧。";s.createdAt=now();segments.insert(s);}
  when(embedding.configured()).thenReturn(true);
  when(embedding.embed(any(),anyList(),anyInt())).thenAnswer(call->{List<String> text=call.getArgument(1);return new QwenEmbeddingClient.Result(text.stream().map(s->{float[] v1=new float[1024];v1[0]=1;return v1;}).toList(),100);});
  doAnswer(call->{objects.put(call.getArgument(1),Files.readAllBytes(call.getArgument(2,Path.class)));return null;}).when(storage).put(anyString(),anyString(),any(),anyString());
  when(storage.bytes(anyString(),anyString(),anyInt())).thenAnswer(call->objects.get(call.getArgument(1,String.class)));
  doAnswer(call->{objects.remove(call.getArgument(1,String.class));return null;}).when(storage).remove(anyString(),anyString());
  when(qdrant.available()).thenReturn(true);when(qdrant.payload(any(),any(),any())).thenCallRealMethod();when(qdrant.matches(any(),any(),any(),any())).thenCallRealMethod();
  doAnswer(call->{SearchIndex i=call.getArgument(0);List<Chunk> batch=call.getArgument(1);for(var chunk:batch)points.put(chunk.pointId,new QdrantIndexClient.Hit(chunk.pointId,chunk.id,0.8,json.valueToTree(qdrant.payload(i,chunk,call.getArgument(3)))));return null;}).when(qdrant).upsert(any(),anyList(),anyList(),any());
  when(qdrant.complete(any(),anyList(),any())).thenAnswer(call->{List<Chunk> all=call.getArgument(1);return !all.isEmpty()&&all.stream().allMatch(c1->points.containsKey(c1.pointId));});
  when(qdrant.query(any(),any(),any())).thenAnswer(call->{SearchIndex i=call.getArgument(0);return chunks.chunks(i.id).stream().limit(call.getArgument(2,RetrievalProperties.Options.class).topK()).map(c1->points.get(c1.pointId)).filter(Objects::nonNull).toList();});
  when(model.answer(any(),anyString(),anyList(),anyList(),anyInt())).thenAnswer(call->{List<RetrievalService.Evidence> evidence=call.getArgument(2);return new QaModelClient.Result(json.writeValueAsString(Map.of("status","ANSWERED","paragraphs",List.of(Map.of("text","有序才能安全排除一半范围。","sourceSegmentIds",List.of(evidence.getFirst().segmentId()))))),100,40);});
 }
 LocalDateTime now(){return LocalDateTime.now(ZoneOffset.UTC);}
 long user(String name){var found=users.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername,name));if(found!=null)return found.getId();var u=new AppUser();u.setUsername(name);u.setDisplayName(name);u.setPasswordHash(passwords.encode("TestPassword@123"));u.setCreatedAt(now());users.insert(u);return u.getId();}
 MockHttpSession login(String name)throws Exception{return(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",name).param("password","TestPassword@123")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);}
 OutboxEvent event(long id){var task=tasks.selectById(id);return outbox.selectList(Wrappers.<OutboxEvent>query().eq("task_id",id).eq("run_no",task.runNo).eq("status","PENDING").orderByDesc("created_at").last("LIMIT 1")).getFirst();}
 void step(long id){var e=event(id);worker.process(e.payloadJson);outbox.sent(e.eventId,now());}
 ProcessingTaskView drain(long id){for(int n=0;n<100;n++){var task=processing.get(owner,id);if("SUCCEEDED".equals(task.status()))return task;assertThat(task.status()).isNotEqualTo("FAILED");step(id);}throw new AssertionError("not finished");}
 long build(){long id=search.submit(owner,video).id();drain(id);return id;}
 SearchIndex index(){return indexes.selectById(states.selectById(video).activeIndexId);}
 Map<String,Object> ask(){return qa.ask(owner,video,UUID.randomUUID().toString(),"为什么需要有序数组？");}

 AgentModelClient.Result tool(String id,String name,String args){
  return new AgentModelClient.Result(AiMessage.from(List.of(ToolExecutionRequest.builder().id(id).name(name).arguments(args).build())),20,10);
 }
 AgentModelClient.Result ready(){return new AgentModelClient.Result(AiMessage.from("READY"),20,2);}
 Map<String,Object> agent(){return qa.ask(owner,video,UUID.randomUUID().toString(),"请先搜索二分查找，再补读前后句解释。","AGENT");}
 AgentModels.Run run(Map<String,Object> turn){return agentRuns.byTurn(((Number)turn.get("id")).longValue());}
 void searchThenRead(){
  var count=new java.util.concurrent.atomic.AtomicInteger();
  when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenAnswer(call->switch(count.getAndIncrement()){
   case 0->tool("search-1","searchSegments","{\"query\":\"二分查找\",\"topK\":1}");
   case 1->tool("read-1","readSurroundingContext","{\"segmentId\":"+segments.transcript(transcript).getFirst().id+",\"before\":0,\"after\":3}");
   default->ready();
  });
 }
 @Test void actualLoopRecordsSearchAndExplicitReadThenValidatedFinal(){
  build();searchThenRead();var turn=agent();var r=run(turn);
  assertThat(turn.get("status")).isEqualTo("SUCCEEDED");assertThat(turn.get("mode")).isEqualTo("AGENT");
  assertThat(r.searchCalls).isEqualTo(1);assertThat(r.contextCalls).isEqualTo(1);assertThat(r.stopReason).isEqualTo("ANSWER_READY");
  var trace=agentSteps.steps(r.id);assertThat(trace).hasSize(6);assertThat(trace).allMatch(s->s.status.equals("SUCCEEDED"));
  var toolSteps=trace.stream().filter(s->s.kind.equals("TOOL")).toList();assertThat(toolSteps).hasSize(2);
  assertThat(toolSteps.getLast().sourceCount).isGreaterThan(toolSteps.getFirst().sourceCount);
  assertThat(turns.selectById(r.turnId).chatRequests).isEqualTo(4);
  verify(model).answer(any(),anyString(),argThat(e->e.size()>1),anyList(),intThat(t->t<=30));
  verify(agentModel,times(3)).decide(any(),anyList(),anyBoolean(),anyInt());
  var protocol=org.mockito.ArgumentCaptor.forClass(List.class);verify(agentModel,times(3)).decide(any(),protocol.capture(),anyBoolean(),anyInt());
  assertThat(protocol.getAllValues().getLast().toString()).contains("search-1","read-1");
 }
 @Test void duplicateToolsUseRunCacheAndStopAfterTwoNoNewResults(){
  build();var n=new java.util.concurrent.atomic.AtomicInteger();
  when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenAnswer(c->tool("t"+n.incrementAndGet(),"searchSegments","{\"query\":\"二分查找\",\"topK\":1}"));
  var r=run(agent());assertThat(r.stopReason).isEqualTo("LOOP_DETECTED");assertThat(r.limited).isTrue();
  assertThat(r.searchCalls).isEqualTo(1);assertThat(r.toolCalls).isEqualTo(3);
  assertThat(agentSteps.steps(r.id).stream().filter(s->s.cached).count()).isEqualTo(2);
 }
 @Test void searchBudgetIncludesOnlyTwoActualEmbeddingCalls(){
  build();var n=new java.util.concurrent.atomic.AtomicInteger();
  when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenAnswer(c->tool("t"+n.incrementAndGet(),"searchSegments","{\"query\":\"二分查找"+n.get()+"\",\"topK\":1}"));
  var turn=agent();var r=run(turn);assertThat(r.searchCalls).isEqualTo(2);assertThat(r.stopReason).isEqualTo("SEARCH_LIMIT");
  assertThat(turn.get("status")).isEqualTo("SUCCEEDED");assertThat(r.limited).isTrue();assertThat(turns.selectById(r.turnId).chatRequests).isLessThanOrEqualTo(5);
 }
 @Test void scopeFieldsAreRejectedBeforeExecutingTool(){
  build();when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenReturn(tool("t1","searchSegments","{\"query\":\"二分\",\"ownerId\":42}"));
  var turn=agent();var r=run(turn);assertThat(turn.get("status")).isEqualTo("FAILED");assertThat(r.searchCalls).isZero();assertThat(r.stopReason).isEqualTo("TOOL_SCOPE_FORBIDDEN");
 }
 @Test void contextMustBeAnchoredInThisRunsActualResults(){
  build();var n=new java.util.concurrent.atomic.AtomicInteger();
  when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenAnswer(c->n.getAndIncrement()==0?tool("a","searchSegments","{\"query\":\"二分\",\"topK\":1}"):tool("b","readSurroundingContext","{\"segmentId\":"+segments.transcript(transcript).getLast().id+"}"));
  var r=run(agent());assertThat(r.stopReason).isEqualTo("TOOL_SOURCE_FORBIDDEN");assertThat(r.contextCalls).isZero();
 }
 @Test void multipleToolRequestsAreNotPartiallyExecuted(){
  build();var a=ToolExecutionRequest.builder().id("a").name("searchSegments").arguments("{\"query\":\"二分\"}").build();
  var b=ToolExecutionRequest.builder().id("b").name("searchSegments").arguments("{\"query\":\"数组\"}").build();
  when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenReturn(new AgentModelClient.Result(AiMessage.from(List.of(a,b)),1,1));
  var r=run(agent());assertThat(r.stopReason).isEqualTo("MULTIPLE_TOOL_CALLS");assertThat(r.toolCalls).isZero();
 }
 @Test void duplicateToolCallIdsStopBeforeSecondExecution(){
  build();when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenReturn(tool("same","searchSegments","{\"query\":\"二分\",\"topK\":1}"));
  var r=run(agent());assertThat(r.stopReason).isEqualTo("INVALID_TOOL_CALL_ID");assertThat(r.toolCalls).isEqualTo(1);
 }
 @Test void malformedArgumentsCanBeCorrectedOnceWithinOriginalBudget(){
  build();when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenReturn(
   tool("bad","searchSegments","{\"query\":\"二分\",\"topK\":100}"),
   tool("good","searchSegments","{\"query\":\"二分\",\"topK\":1}"),ready());
  var turn=agent();var r=run(turn);assertThat(turn.get("status")).isEqualTo("SUCCEEDED");assertThat(r.toolCalls).isEqualTo(2);assertThat(r.searchCalls).isEqualTo(1);
  assertThat(agentSteps.steps(r.id).stream().filter(s->s.status.equals("REJECTED")).count()).isEqualTo(1);
 }
 @Test void repeatedInvalidArgumentsStopAfterOneCorrection(){
  build();when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenReturn(tool("a","searchSegments","{\"query\":\"二分\",\"topK\":100}"),tool("b","searchSegments","{\"query\":\"二分\",\"topK\":100}"));
  var r=run(agent());assertThat(r.toolCalls).isEqualTo(2);assertThat(r.searchCalls).isZero();assertThat(r.status).isEqualTo("FAILED");
 }
 @Test void unknownToolDoesNotRunAnyExternalSearch(){
  build();when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenReturn(tool("a","executeSql","{\"query\":\"SELECT 1\"}"));
  assertThat(run(agent()).searchCalls).isZero();
 }
 @Test void forgedFinalCitationIsNeverDisplayed(){
  build();when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenReturn(tool("a","searchSegments","{\"query\":\"二分\",\"topK\":1}"),ready());
  doReturn(new QaModelClient.Result("{\"status\":\"ANSWERED\",\"paragraphs\":[{\"text\":\"fake\",\"sourceSegmentIds\":[999999]}]}",20,5)).when(model).answer(any(),anyString(),anyList(),anyList(),anyInt());
  var turn=agent();assertThat(turn.get("status")).isEqualTo("FAILED");assertThat(turn.get("answer")).isNull();assertThat(run(turn).stopReason).isEqualTo("INVALID_ANSWER");
 }
 @Test void modelTimeoutRemainsUnknownAndSameRequestDoesNotResend(){
  build();when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenThrow(new LearningFailure("AGENT_MODEL_UNKNOWN","结果不确定",false,true));
  String id=UUID.randomUUID().toString();var result=qa.ask(owner,video,id,"二分是什么","AGENT");
  assertThat(result.get("status")).isEqualTo("UNKNOWN");qa.ask(owner,video,id,"二分是什么","AGENT");
  verify(agentModel,times(1)).decide(any(),anyList(),anyBoolean(),anyInt());
 }
 @Test void twoEmptySearchesReturnInsufficientWithoutFinalGeneration(){
  build();doReturn(List.of()).when(qdrant).query(any(),any(),any());
  when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenReturn(tool("a","searchSegments","{\"query\":\"量子计算\"}"),tool("b","searchSegments","{\"query\":\"量子\"}"));
  var turn=agent();assertThat(turn.get("status")).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(run(turn).searchCalls).isEqualTo(2);
  verifyNoInteractions(model);
 }
 @Test void expiredRunsAndStartedStepsReconcileWithoutNewModelCalls(){
  build();searchThenRead();var turn=agent();var r=run(turn);
  jdbc.update("UPDATE chat_turn SET status='RUNNING',deadline_at=? WHERE id=?",now().minusSeconds(1),r.turnId);
  jdbc.update("UPDATE agent_run SET status='RUNNING',deadline_at=? WHERE id=?",now().minusSeconds(1),r.id);
  jdbc.update("UPDATE agent_step SET status='STARTED' WHERE run_id=?",r.id);clearInvocations(agentModel);
  assertThat(qa.get(owner,video,r.turnId).get("status")).isEqualTo("UNKNOWN");assertThat(agentRuns.byTurn(r.turnId).status).isEqualTo("UNKNOWN");
  assertThat(agentSteps.steps(r.id)).allMatch(s->s.status.equals("UNKNOWN"));verifyNoInteractions(agentModel);
 }
 @Test void requestIdCannotChangeModeAndOldDefaultRemainsRag(){
  build();String id=UUID.randomUUID().toString();var ordinary=qa.ask(owner,video,id,"为什么有序");
  assertThat(ordinary.get("mode")).isEqualTo("RAG");
  assertThatThrownBy(()->qa.ask(owner,video,id,"为什么有序","AGENT")).isInstanceOf(ApiException.class);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_run",Integer.class)).isZero();
 }
 @Test void ragAndAgentShareSixRequestsPerMinute(){
  build();when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenThrow(new LearningFailure("AGENT_MODEL_UNKNOWN","unknown",false,true));
  for(int n=0;n<6;n++)qa.ask(owner,video,UUID.randomUUID().toString(),"问题"+n,n%2==0?"RAG":"AGENT");
  assertThatThrownBy(()->qa.ask(owner,video,UUID.randomUUID().toString(),"第七个","AGENT")).isInstanceOf(ApiException.class);
  assertThat(turns.recentCount(owner,now().minusMinutes(1))).isEqualTo(6);
 }
 @Test void runningAgentBlocksRagButSameIdReadsRunning()throws Exception{
  build();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenAnswer(c->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return ready();});
  String id=UUID.randomUUID().toString();var executor=Executors.newSingleThreadExecutor();
  try{
   var future=executor.submit(()->qa.ask(owner,video,id,"阻塞","AGENT"));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
   assertThat(qa.ask(owner,video,id,"阻塞","AGENT").get("status")).isEqualTo("RUNNING");
   assertThatThrownBy(()->qa.ask(owner,video,UUID.randomUUID().toString(),"普通问题")).isInstanceOf(ApiException.class);
   release.countDown();future.get(10,TimeUnit.SECONDS);
  }finally{release.countDown();executor.shutdownNow();}
 }
 @Test void traceOwnershipAndCsrfAreEnforced()throws Exception{
  build();searchThenRead();var r=run(agent());
  mvc.perform(get("/api/videos/"+video+"/questions/"+r.turnId+"/agent-run").session(alice)).andExpect(status().isOk()).andExpect(jsonPath("$.data.steps").isArray());
  mvc.perform(get("/api/videos/"+video+"/questions/"+r.turnId+"/agent-run").session(bob)).andExpect(status().isNotFound());
  mvc.perform(get("/api/videos/"+video+"/questions/"+r.turnId+"/agent-run")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/videos/"+video+"/questions").session(alice).contentType("application/json").content("{\"requestId\":\"0123456789abcdef\",\"question\":\"课程\",\"mode\":\"AGENT\"}")).andExpect(status().isForbidden());
 }
 @Test void longContextOffsetsRemainCanonicalAndUnicodeSafe(){
  build();var s=segments.transcript(transcript).getFirst();s.text="甲".repeat(1199)+"😀"+"乙".repeat(2000);segments.updateById(s);
  var first=contextRead.read(index(),s.id,0,0,0).getFirst();assertThat(first.endOffset()).isEqualTo(1199);
  var next=contextRead.read(index(),s.id,0,0,1199).getFirst();assertThat(next.text()).startsWith("😀");assertThat(next.beginMs()).isEqualTo(s.beginMs);
  assertThatThrownBy(()->contextRead.read(index(),s.id,0,0,1200)).isInstanceOf(LearningFailure.class);
 }
 @Test void toolOutputAndEvidenceDoNotInventUnreadGap(){
  var ledger=new EvidenceLedger();
  ledger.add(List.of(new RetrievalService.Evidence(1,0,3,"abc",0,1),new RetrievalService.Evidence(1,6,9,"ghi",0,1)));
  assertThat(ledger.finalEvidence()).hasSize(1);assertThat(ledger.finalEvidence().getFirst().text()).doesNotContain("def");
  ledger.add(List.of(new RetrievalService.Evidence(1,2,7,"cdefg",0,1)));
  assertThat(ledger.finalEvidence().getFirst().text()).isEqualTo("abcdefghi");
  var output=toolService.bounded(List.of(new RetrievalService.Evidence(2,0,4000,"字".repeat(4000),1,2)),0);
  assertThat(output.json().length()).isLessThanOrEqualTo(2500);assertThat(AgentToolService.bytes(output.json())).isLessThanOrEqualTo(12000);
  assertThat(output.evidence().getFirst().text().length()).isLessThan(4000);
 }
 @Test void qdrantFaultProducesFailureRecordWithoutRetranscription(){
  build();doThrow(new LearningFailure("QDRANT_UNAVAILABLE","检索不可用",true)).when(qdrant).query(any(),any(),any());
  when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenReturn(tool("a","searchSegments","{\"query\":\"二分\"}"));
  var r=run(agent());assertThat(r.status).isEqualTo("FAILED");assertThat(r.stopReason).isEqualTo("QDRANT_UNAVAILABLE");
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asr_job",Integer.class)).isZero();verifyNoInteractions(model);
 }
 @Test void lowerRoundBudgetStillReservesFinalCall(){
  build();searchThenRead();agentProperties.setMaxRounds(2);
  try{
   var turn=agent();var r=run(turn);assertThat(r.rounds).isEqualTo(2);assertThat(r.stopReason).isEqualTo("ROUND_LIMIT");assertThat(r.limited).isTrue();
   assertThat(turn.get("status")).isEqualTo("SUCCEEDED");assertThat(turns.selectById(r.turnId).chatRequests).isEqualTo(3);assertThat(r.toolCalls).isEqualTo(2);
  }finally{agentProperties.setMaxRounds(4);}
 }
 @Test void frozenRunDoesNotFollowAnActivePointerChange(){
  build();searchThenRead();
  doAnswer(call->{jdbc.update("UPDATE video_search_state SET active_index_id=NULL WHERE video_id=?",video);return tool("a","searchSegments","{\"query\":\"二分\",\"topK\":1}");})
   .doReturn(ready()).when(agentModel).decide(any(),anyList(),anyBoolean(),anyInt());
  var turn=agent();assertThat(turn.get("status")).isEqualTo("SUCCEEDED");assertThat(run(turn).indexId).isEqualTo(turn.get("indexId"));
 }
 @Test void expiredExecutionCannotPublishLateAnswer(){
  build();
  when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenAnswer(c->{
   jdbc.update("UPDATE chat_turn SET deadline_at=? WHERE owner_id=? AND status='RUNNING'",now().minusSeconds(1),owner);
   return tool("a","searchSegments","{\"query\":\"二分\"}");
  });
  var turn=agent();assertThat(turn.get("status")).isEqualTo("UNKNOWN");assertThat(turn.get("answer")).isNull();assertThat(run(turn).searchCalls).isZero();verifyNoInteractions(model);
 }
 @Test void focusKeepsLateRelevantSourcesWithinValidatedHits(){
  var hits=new ArrayList<RetrievalService.Evidence>();
  for(int n=0;n<40;n++)hits.add(new RetrievalService.Evidence(n+1,0,20,"这是普通的课程介绍和其他无关学习资料内容",n,n+1));
  hits.add(new RetrievalService.Evidence(100,0,8,"循环队列空满判断",50,51));
  var result=toolService.focus(hits,"循环队列");assertThat(result).extracting(RetrievalService.Evidence::segmentId).containsExactly(100L);
 }
 @Test void numericStringsAreRejectedAndNullableOptionalFieldsUseDefaults(){
  assertThatThrownBy(()->toolService.prepare("searchSegments","{\"query\":\"数组\",\"topK\":\"1\"}",new EvidenceLedger())).isInstanceOf(LearningFailure.class);
  assertThat(toolService.prepare("searchSegments","{\"query\":\"数组\",\"topK\":null}",new EvidenceLedger()).topK()).isEqualTo(2);
 }
 @Test void savedTraceSourceIsRevalidatedBeforeProvidingJumpTargets(){
  build();searchThenRead();var r=run(agent());var step=agentSteps.steps(r.id).stream().filter(s->s.kind.equals("TOOL")).findFirst().orElseThrow();
  step.resultJson="{\"sources\":[{\"segmentId\":999999,\"startOffset\":0,\"endOffset\":1,\"text\":\"假\",\"beginMs\":0,\"endMs\":1}]}";agentSteps.updateById(step);
  assertThatThrownBy(()->qa.agentRun(owner,video,r.turnId)).isInstanceOf(ApiException.class);
 }
 @Test void oneRoundWithRejectedToolEndsAsLimitWithoutFinalCall(){
  build();agentProperties.setMaxRounds(1);
  try{
   when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenReturn(tool("a","searchSegments","{\"query\":\"二分\",\"topK\":99}"));
   var turn=agent();assertThat(turn.get("status")).isEqualTo("LIMIT_REACHED");assertThat(run(turn).rounds).isEqualTo(1);verifyNoInteractions(model);
  }finally{agentProperties.setMaxRounds(4);}
 }
 @Test void invalidLongSentenceOffsetCanBeCorrectedWithinReadBudget(){
  build();long anchor=segments.transcript(transcript).getFirst().id;
  when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenReturn(
   tool("a","searchSegments","{\"query\":\"二分\",\"topK\":1}"),
   tool("b","readSurroundingContext","{\"segmentId\":"+anchor+",\"startOffset\":999}"),
   tool("c","readSurroundingContext","{\"segmentId\":"+anchor+",\"startOffset\":0}"),ready());
  var turn=agent();var r=run(turn);assertThat(turn.get("status")).isEqualTo("SUCCEEDED");assertThat(r.contextCalls).isEqualTo(2);
  assertThat(r.rounds).isEqualTo(4);assertThat(r.toolCalls).isEqualTo(3);
  assertThat(agentSteps.steps(r.id).stream().filter(s->s.status.equals("REJECTED")).count()).isEqualTo(1);
 }
 @Test void limitedCollectionCannotMasqueradeAsDefinitiveNoEvidence(){
  build();agentProperties.setMaxRounds(1);
  try{
   when(agentModel.decide(any(),anyList(),anyBoolean(),anyInt())).thenReturn(tool("a","searchSegments","{\"query\":\"二分\",\"topK\":1}"));
   doReturn(new QaModelClient.Result("{\"status\":\"INSUFFICIENT_EVIDENCE\",\"paragraphs\":[]}",1,1)).when(model).answer(any(),anyString(),anyList(),anyList(),anyInt());
   var turn=agent();assertThat(turn.get("status")).isEqualTo("LIMIT_REACHED");assertThat(turn.get("answer")).isNull();
   assertThat(run(turn).stopReason).isEqualTo("ROUND_LIMIT");
  }finally{agentProperties.setMaxRounds(4);}
 }
}
