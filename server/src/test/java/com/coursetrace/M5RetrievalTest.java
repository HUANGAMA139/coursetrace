package com.coursetrace;
import com.coursetrace.retrieval.*;
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

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:coursetrace_m5;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "coursetrace.learning.api-key=unit-test-placeholder","coursetrace.retrieval.target-chars=100",
 "coursetrace.retrieval.max-chars=160","coursetrace.retrieval.overlap-chars=20","coursetrace.retrieval.batch-size=2"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class M5RetrievalTest {
 @Autowired SearchIndexService search;@Autowired CourseQaService qa;@Autowired ProcessingService processing;@Autowired ProcessingWorker worker;
 @Autowired ProcessingMapper tasks;@Autowired TaskStepMapper steps;@MockitoSpyBean OutboxMapper outbox;
 @Autowired SearchIndexMapper indexes;@MockitoSpyBean SearchStateMapper states;@Autowired ChunkMapper chunks;@Autowired ChatTurnMapper turns;
 @Autowired ArtifactMapper artifacts;@Autowired SegmentMapper segments;@Autowired CourseMapper courses;@Autowired CourseVideoMapper videos;
 @Autowired AppUserMapper users;@Autowired PasswordEncoder passwords;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;
 @MockitoBean QwenEmbeddingClient embedding;@MockitoBean QaModelClient model;@MockitoBean QdrantIndexClient qdrant;@MockitoBean MediaStorage storage;
 long owner,course,video,transcript;MockHttpSession alice,bob;
 Map<String,byte[]> objects=new ConcurrentHashMap<>();Map<String,QdrantIndexClient.Hit> points=new ConcurrentHashMap<>();
 @BeforeEach void setup() throws Exception {
  for(String table:List.of("chat_turn","subtitle_chunk_segment","subtitle_chunk","video_search_state","video_search_index","course_note","course_segment","asr_job","processing_artifact","outbox_event","task_step","processing_task","course_video","course"))jdbc.update("DELETE FROM "+table);
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
  when(qdrant.query(any(),any(),any())).thenAnswer(call->{SearchIndex i=call.getArgument(0);return chunks.chunks(i.id).stream().limit(6).map(c1->points.get(c1.pointId)).filter(Objects::nonNull).toList();});
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
 @Test void completePipelineIsIdempotentAndReusesTranscriptWithoutAsr(){
  long id=search.submit(owner,video).id();
  for(int n=0;n<100;n++){if("SUCCEEDED".equals(tasks.selectById(id).status))break;var e=event(id);worker.process(e.payloadJson);worker.process(e.payloadJson);outbox.sent(e.eventId,now());}
  assertThat(search.status(owner,video).get("ready")).isEqualTo(true);
  assertThat(search.submit(owner,video).id()).isEqualTo(id);
  assertThat(index().batchCount).isGreaterThan(1);verify(embedding,times(index().batchCount)).embed(any(),anyList(),anyInt());
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asr_job",Integer.class)).isZero();
  assertThat(segments.transcript(transcript)).hasSize(8);
  assertThat(processing.batch(owner,course,List.of(video),3)).hasSize(1);
 }
 @Test void qdrantFailureRecoversFromPersistedEmbedding(){
  long id=search.submit(owner,video).id();step(id);step(id);int calls=mockingDetails(embedding).getInvocations().size();
  doThrow(new LearningFailure("QDRANT_UNAVAILABLE","检索服务暂时不可用",true)).when(qdrant).upsert(any(),anyList(),anyList(),any());
  var e=event(id);assertThatThrownBy(()->worker.process(e.payloadJson)).isInstanceOf(ProcessingFailures.Retryable.class);worker.deadLetter(e.payloadJson);
  assertThat(search.status(owner,video).get("ready")).isEqualTo(false);
  reset(qdrant);when(qdrant.payload(any(),any(),any())).thenCallRealMethod();when(qdrant.matches(any(),any(),any(),any())).thenCallRealMethod();
  doAnswer(call->{SearchIndex i=call.getArgument(0);List<Chunk> batch=call.getArgument(1);for(var c:batch)points.put(c.pointId,new QdrantIndexClient.Hit(c.pointId,c.id,1,json.valueToTree(qdrant.payload(i,c,call.getArgument(3)))));return null;}).when(qdrant).upsert(any(),anyList(),anyList(),any());
  when(qdrant.complete(any(),anyList(),any())).thenAnswer(call->{List<Chunk> all=call.getArgument(1);return all.stream().allMatch(c->points.containsKey(c.pointId));});
  processing.retry(owner,id,tasks.selectById(id).runNo);drain(id);
  verify(embedding,times(index().batchCount)).embed(any(),anyList(),anyInt());assertThat(calls).isGreaterThan(0);
 }
 @Test void nextOutboxRollbackDoesNotLeavePartialChunks(){
  long id=search.submit(owner,video).id();doThrow(new IllegalStateException("injected")).when(outbox).insert(any(OutboxEvent.class));
  var e=event(id);assertThatThrownBy(()->worker.process(e.payloadJson)).isInstanceOf(ProcessingFailures.Retryable.class);
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM subtitle_chunk",Integer.class)).isZero();
  reset(outbox);drain(id);assertThat(index().status).isEqualTo("READY");
 }
 @Test void publishRollbackCanRetryWithoutExtraEmbedding(){
  long id=search.submit(owner,video).id();
  while(!tasks.selectById(id).currentStage.equals("INDEX_PUBLISH"))step(id);
  doThrow(new IllegalStateException("injected")).when(states).save(any(SearchState.class));
  var e=event(id);assertThatThrownBy(()->worker.process(e.payloadJson)).isInstanceOf(ProcessingFailures.Retryable.class);
  assertThat(states.selectById(video).activeIndexId).isNull();reset(states);drain(id);
  verify(embedding,times(index().batchCount)).embed(any(),anyList(),anyInt());
 }
 @Test void rebuildingUsesCachedVectorsAndOldEventsCannotPublish(){
  long task=build();var old=index();var oldEvent=outbox.selectList(Wrappers.<OutboxEvent>query().eq("task_id",task).last("LIMIT 1")).getFirst();
  String request=UUID.randomUUID().toString();var run=search.rebuild(owner,video,old.id,1,request);
  assertThat(run.runNo()).isEqualTo(2);assertThat(search.rebuild(owner,video,old.id,1,request).runNo()).isEqualTo(2);
  assertThat(search.status(owner,video).get("ready")).isEqualTo(true);worker.process(oldEvent.payloadJson);drain(task);
  assertThat(index().id).isNotEqualTo(old.id);verify(embedding,times(old.batchCount)).embed(any(),anyList(),anyInt());
 }
 @Test void lostPointsMakeReadyIndexDegradedAndCanRebuild(){
  long task=build();var old=index();points.clear();assertThat(search.status(owner,video).get("status")).isEqualTo("DEGRADED");
  search.rebuild(owner,video,old.id,1,UUID.randomUUID().toString());drain(task);assertThat(search.status(owner,video).get("ready")).isEqualTo(true);
  verify(embedding,times(old.batchCount)).embed(any(),anyList(),anyInt());
 }
 @Test void embeddingBudgetSurvivesManualRuns(){
  long task=search.submit(owner,video).id();step(task);when(embedding.embed(any(),anyList(),anyInt())).thenThrow(new LearningFailure("EMBEDDING_UNAVAILABLE","模型暂不可用",true));
  var initial=event(task);for(int n=0;n<3;n++)assertThatThrownBy(()->worker.process(initial.payloadJson)).isInstanceOf(ProcessingFailures.Retryable.class);worker.deadLetter(initial.payloadJson);
  processing.retry(owner,task,1);step(task);var retried=event(task);final String payload=retried.payloadJson;
  assertThatThrownBy(()->worker.process(payload)).isInstanceOf(ProcessingFailures.Permanent.class);worker.deadLetter(payload);
  assertThat(processing.get(owner,task).canRetry()).isFalse();verify(embedding,times(3)).embed(any(),anyList(),anyInt());
 }
 @Test void questionHasRealCitationsAndRequestReplayDoesNotChargeAgain()throws Exception{
  build();String request=UUID.randomUUID().toString();var result=qa.ask(owner,video,request,"为什么需要有序数组？");
  assertThat(result.get("status")).isEqualTo("SUCCEEDED");
  var node=json.valueToTree(result);var citation=node.path("answer").path("paragraphs").get(0).path("citations").get(0);
  var canonical=segments.selectById(citation.path("segmentId").asLong());assertThat(citation.path("beginMs").asLong()).isEqualTo(canonical.beginMs);
  assertThat(citation.path("quote").asText()).isEqualTo(canonical.text);
  assertThat(qa.ask(owner,video,request,"为什么需要有序数组？").get("id")).isEqualTo(result.get("id"));
  verify(model,times(1)).answer(any(),anyString(),anyList(),anyList(),anyInt());
  assertThat(qa.history(owner,video,1,20).get("total")).isEqualTo(1);
  assertThat(json.writeValueAsString(result)).doesNotContain("unit-test-placeholder","contextJson","bucketName","executionToken");
 }
 @Test void fabricatedCitationFailsClosed(){
  build();doReturn(new QaModelClient.Result("{\"status\":\"ANSWERED\",\"paragraphs\":[{\"text\":\"unsafe\",\"sourceSegmentIds\":[999999]}]}",10,10)).when(model).answer(any(),anyString(),anyList(),anyList(),anyInt());
  var result=ask();assertThat(result.get("status")).isEqualTo("FAILED");assertThat(result.get("errorCode")).isEqualTo("INVALID_ANSWER");assertThat(result.get("answer")).isNull();
 }
 @Test void insufficientEvidenceIsStoredWithoutUnsourcedText(){
  build();doReturn(new QaModelClient.Result("{\"status\":\"INSUFFICIENT_EVIDENCE\",\"paragraphs\":[{\"text\":\"invented\"}]}",10,10)).when(model).answer(any(),anyString(),anyList(),anyList(),anyInt());
  var result=ask();assertThat(result.get("status")).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(json.valueToTree(result).path("answer").path("paragraphs")).isEmpty();
 }
 @Test void noRecallSkipsChatAndExpiredTurnNeverResubmits(){
  build();doReturn(List.of()).when(qdrant).query(any(),any(),any());var result=ask();verifyNoInteractions(model);
  long id=((Number)result.get("id")).longValue();var turn=turns.selectById(id);jdbc.update("UPDATE chat_turn SET status='RUNNING',deadline_at=? WHERE id=?",now().minusSeconds(1),id);
  assertThat(qa.get(owner,video,id).get("status")).isEqualTo("UNKNOWN");
  qa.ask(owner,video,turn.requestId,turn.question);verifyNoInteractions(model);
 }
 @Test void perUserQuotaRejectsSeventhQuestion(){
  build();for(int n=0;n<6;n++)ask();assertThatThrownBy(this::ask).isInstanceOf(ApiException.class).extracting(e->((ApiException)e).getStatus().value()).isEqualTo(429);
 }
 @Test void recentConversationIsBoundedAndDifferentRequestCannotReuseId(){
  build();String request=UUID.randomUUID().toString();qa.ask(owner,video,request,"解释二分查找");ask();
  verify(model).answer(any(),anyString(),argThat(e->!e.isEmpty()),argThat(h->h.size()==1),anyInt());
  assertThatThrownBy(()->qa.ask(owner,video,request,"另一个问题")).isInstanceOf(ApiException.class);
 }
 @Test void corruptedSavedCitationIsNotDisplayed(){
  build();var result=ask();long id=((Number)result.get("id")).longValue();jdbc.update("UPDATE chat_turn SET context_json='[]' WHERE id=?",id);
  assertThat(qa.get(owner,video,id).get("status")).isEqualTo("FAILED");assertThat(qa.get(owner,video,id).get("answer")).isNull();
 }
 @Test void wrongOwnerAnonymousAndCsrfAreRejected()throws Exception{
  build();var result=ask();long id=((Number)result.get("id")).longValue();
  for(String route:List.of("/search-index","/questions","/questions/"+id)){
   mvc.perform(get("/api/videos/"+video+route).session(bob)).andExpect(status().isNotFound());
   mvc.perform(get("/api/videos/"+video+route)).andExpect(status().isUnauthorized());
  }
  mvc.perform(post("/api/videos/"+video+"/questions").session(alice).contentType("application/json").content("{\"requestId\":\""+UUID.randomUUID()+"\",\"question\":\"test\"}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/videos/"+video+"/search-index").session(bob).with(csrf())).andExpect(status().isNotFound());
 }
 @Test void missingTranscriptAndChangedTranscriptBlockQuestions(){
  jdbc.update("DELETE FROM task_step");assertThatThrownBy(()->search.submit(owner,video)).isInstanceOf(ApiException.class);verify(embedding,never()).embed(any(),anyList(),anyInt());
 }
 @Test void forgedQdrantPayloadIsRejectedEvenWhenChunkIdExists(){
  build();var existing=points.values().iterator().next();var payload=existing.payload().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)payload).put("owner_id",owner+100);
  doReturn(List.of(new QdrantIndexClient.Hit(existing.pointId(),existing.chunkId(),1,payload))).when(qdrant).query(any(),any(),any());
  var answer=ask();assertThat(answer.get("status")).isEqualTo("FAILED");assertThat(answer.get("errorCode")).isEqualTo("INDEX_SOURCE_INVALID");verifyNoInteractions(model);
 }
 @Test void staleRebuildAndOldDeadLetterCannotChangeNewRun(){
  long task=build();var old=index();search.rebuild(owner,video,old.id,1,UUID.randomUUID().toString());
  assertThatThrownBy(()->search.rebuild(owner,video,old.id,1,UUID.randomUUID().toString())).isInstanceOf(ApiException.class);
  var event=outbox.selectList(Wrappers.<OutboxEvent>query().eq("task_id",task).eq("run_no",1).last("LIMIT 1")).getFirst();worker.deadLetter(event.payloadJson);
  assertThat(tasks.selectById(task).runNo).isEqualTo(2);drain(task);assertThat(index().status).isEqualTo("READY");
 }

 @Test void manyShortAsrSentencesRemainAvailableBeyondSixteenSources(){
  jdbc.update("UPDATE course_video SET duration_ms=120000 WHERE id=?",video);
  jdbc.update("UPDATE course_segment SET text='短句内容，先进入队列的元素先取出' WHERE transcript_artifact_id=?",transcript);
  for(int n=8;n<28;n++){var s=new Segment();s.videoId=video;s.transcriptArtifactId=transcript;s.ordinal=n;s.beginMs=n*2000+100;s.endMs=n*2000+1800;s.text="短句内容，先进入队列的元素先取出";s.createdAt=now();segments.insert(s);}
  build();ask();
  verify(model).answer(any(),anyString(),argThat(e->e.size()>16),anyList(),anyInt());
 }
 @Test void concurrentDuplicateReturnsInProgressAndSecondQuestionIsRejected()throws Exception{
  build();String request=UUID.randomUUID().toString();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
  doAnswer(call->{entered.countDown();release.await(5,TimeUnit.SECONDS);List<RetrievalService.Evidence> e=call.getArgument(2);
   return new QaModelClient.Result(json.writeValueAsString(Map.of("status","ANSWERED","paragraphs",List.of(Map.of("text","有序数组","sourceSegmentIds",List.of(e.getFirst().segmentId()))))),10,10);
  }).when(model).answer(any(),anyString(),anyList(),anyList(),anyInt());
  var first=CompletableFuture.supplyAsync(()->qa.ask(owner,video,request,"解释二分查找"));
  try{
   assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
   assertThat(qa.ask(owner,video,request,"解释二分查找").get("status")).isEqualTo("RUNNING");
   assertThatThrownBy(()->qa.ask(owner,video,UUID.randomUUID().toString(),"另一个问题")).isInstanceOf(ApiException.class);
  }finally{release.countDown();}
  assertThat(first.get(5,TimeUnit.SECONDS).get("status")).isEqualTo("SUCCEEDED");verify(model,times(1)).answer(any(),anyString(),anyList(),anyList(),anyInt());
 }
 @Test void changedTranscriptCannotUseOldIndexOrChargeForAQuestion()throws Exception{
  build();var artifact=new Artifact();artifact.videoId=video;artifact.kind="ASR_JSON";artifact.fingerprint=LearningJson.hash("new"+video);artifact.state="READY";artifact.createdAt=now();artifact.updatedAt=now();artifacts.insert(artifact);
  var task=tasks.findVersion(video,2);task.runNo=2;tasks.save(task);
  var step=new TaskStep();step.taskId=task.id;step.runNo=2;step.stage="TRANSCRIPT_BUILD";step.status="SUCCEEDED";step.maxAttempts=3;step.resultJson=json.writeValueAsString(Map.of("transcriptArtifactId",artifact.id,"segmentCount",1));step.createdAt=now();step.updatedAt=now();steps.insert(step);
  assertThat(search.status(owner,video).get("status")).isEqualTo("STALE");
  assertThatThrownBy(this::ask).isInstanceOf(ApiException.class);verifyNoInteractions(model);
 }

 @Test void unavailableQdrantReturns503BeforeAnyQuestionIsCreated()throws Exception{
  build();doThrow(new LearningFailure("QDRANT_UNAVAILABLE","检索服务暂时不可用",true)).when(qdrant).complete(any(),anyList(),any());
  mvc.perform(post("/api/videos/"+video+"/questions").session(alice).with(csrf()).contentType("application/json")
   .content("{\"requestId\":\""+UUID.randomUUID()+"\",\"question\":\"解释二分查找\"}")).andExpect(status().isServiceUnavailable());
  assertThat(turns.count(owner,video)).isZero();verifyNoInteractions(model);
 }

 @Test void requestLookupIsReadOnlyEvenWhenSearchIndexIsUnavailable()throws Exception{
  build();String request=UUID.randomUUID().toString();var answer=qa.ask(owner,video,request,"解释二分查找");
  clearInvocations(embedding,model,qdrant);
  doThrow(new LearningFailure("QDRANT_UNAVAILABLE","offline",true)).when(qdrant).complete(any(),anyList(),any());
  mvc.perform(get("/api/videos/"+video+"/questions/request/"+request).session(alice))
   .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(((Number)answer.get("id")).longValue()))
   .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
  assertThat(turns.count(owner,video)).isEqualTo(1);
  verifyNoInteractions(embedding,model,qdrant);
 }
 @Test void absentOrForeignRequestLookupNeverCreatesOrChargesAQuestion()throws Exception{
  String request=UUID.randomUUID().toString();
  mvc.perform(get("/api/videos/"+video+"/questions/request/"+request).session(alice)).andExpect(status().isNotFound());
  mvc.perform(get("/api/videos/"+video+"/questions/request/"+request).session(bob)).andExpect(status().isNotFound());
  mvc.perform(get("/api/videos/"+video+"/questions/request/"+request)).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/videos/"+video+"/questions/request/bad").session(alice)).andExpect(status().isBadRequest());
  assertThat(turns.count(owner,video)).isZero();verifyNoInteractions(embedding,model,qdrant);
 }
 @Test void requestLookupObservesRunningAndExpiredRecordsWithoutResubmission()throws Exception{
  build();String request=UUID.randomUUID().toString();var answer=qa.ask(owner,video,request,"解释二分查找");long id=((Number)answer.get("id")).longValue();
  clearInvocations(embedding,model,qdrant);
  jdbc.update("UPDATE chat_turn SET status='RUNNING',deadline_at=? WHERE id=?",now().plusSeconds(30),id);
  assertThat(qa.request(owner,video,request).get("status")).isEqualTo("RUNNING");
  jdbc.update("UPDATE chat_turn SET deadline_at=? WHERE id=?",now().minusSeconds(1),id);
  assertThat(qa.request(owner,video,request).get("status")).isEqualTo("UNKNOWN");
  verifyNoInteractions(embedding,model,qdrant);
 }

}