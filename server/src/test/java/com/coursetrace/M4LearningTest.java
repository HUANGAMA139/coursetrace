package com.coursetrace;

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
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:coursetrace_m4;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "coursetrace.learning.api-key=unit-test-placeholder","coursetrace.learning.ffmpeg-path=${java.home}/bin/java.exe",
    "coursetrace.learning.window-chars=200"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class M4LearningTest {
    @Autowired ProcessingService processing;
    @Autowired ProcessingWorker worker;
    @Autowired ProcessingMapper tasks;
    @Autowired TaskStepMapper steps;
    @MockitoSpyBean OutboxMapper outbox;
    @Autowired ArtifactMapper artifacts;
    @Autowired AsrJobMapper jobs;
    @Autowired SegmentMapper segments;
    @Autowired NoteMapper notes;
    @Autowired LearningReadService read;
    @Autowired CourseMapper courses;
    @Autowired CourseVideoMapper videos;
    @Autowired AppUserMapper users;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean QwenAsrClient cloud;
    @MockitoBean NoteModelClient model;
    @MockitoBean MediaStorage storage;
    @MockitoBean AudioExtractor extractor;
    long ownerId,courseId,videoId;
    MockHttpSession alice,bob;
    Map<String,byte[]> objects=new HashMap<>();

    @BeforeEach void prepare() throws Exception {
        for(String table:List.of("course_note","course_segment","asr_job","processing_artifact","outbox_event","task_step","processing_task","course_video","course"))jdbc.update("DELETE FROM "+table);
        ownerId=user("m4alice");user("m4bob");alice=login("m4alice");bob=login("m4bob");
        var course=new Course();course.setOwnerId(ownerId);course.setTitle("字幕测试");course.setDescription("");course.setCreatedAt(LocalDateTime.now());course.setUpdatedAt(LocalDateTime.now());courses.insert(course);courseId=course.getId();
        var video=new CourseVideo();video.setCourseId(courseId);video.setTitle("有声课程");video.setOriginalFilename("lesson.mp4");video.setBucketName("test-videos");video.setObjectKey("lesson.mp4");
        video.setSizeBytes(100L);video.setContentType("video/mp4");video.setDurationMs(12000L);video.setWidth(640);video.setHeight(360);video.setVideoCodec("h264");video.setAudioCodec("aac");video.setCreatedAt(LocalDateTime.now());videos.insert(video);videoId=video.getId();
        when(cloud.configured()).thenReturn(true);
        when(storage.size(anyString(),anyString())).thenReturn(100L);
        doAnswer(call->{Files.write(call.getArgument(2,Path.class),new byte[10]);return null;}).when(storage).download(anyString(),anyString(),any(),anyLong());
        doAnswer(call->{objects.put(call.getArgument(1),Files.readAllBytes(call.getArgument(2,Path.class)));return null;}).when(storage).put(anyString(),anyString(),any(),anyString());
        when(storage.bytes(anyString(),anyString(),anyInt())).thenAnswer(call->objects.get(call.getArgument(1,String.class)));
        when(extractor.extract(any())).thenAnswer(call->{
            var audio=new Artifact();audio.videoId=videoId;audio.kind="AUDIO";audio.fingerprint=LearningJson.hash("audio-"+videoId);
            audio.state="READY";audio.bucketName="test-videos";audio.objectKey="audio.flac";audio.sizeBytes=10;audio.metadataJson="{\"audioOriginMs\":0,\"durationMs\":12000}";
            audio.createdAt=LocalDateTime.now();audio.updatedAt=audio.createdAt;return audio;
        });
        when(cloud.upload(any(),any())).thenReturn("oss://unit/audio.flac");
        when(cloud.submit(anyString(),any())).thenReturn("remote-"+UUID.randomUUID());
        when(cloud.poll(anyString(),any())).thenReturn(json.readTree("{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{\"subtask_status\":\"SUCCEEDED\",\"transcription_url\":\"https://result.oss-cn-beijing.aliyuncs.com/result.json\"}]}}"));
        when(cloud.result(anyString())).thenReturn(raw("第一段讲解数组必须有序。","第二段讲解每次比较中间元素。","第三段讲解搜索范围缩小一半。"));
        doAnswer(call->validNote(call.getArgument(1))).when(model).generate(any(),anyList(),anyBoolean());
    }
    long user(String name){
        var found=users.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername,name));if(found!=null)return found.getId();
        var u=new AppUser();u.setUsername(name);u.setDisplayName(name);u.setPasswordHash(passwords.encode("TestPassword@123"));u.setCreatedAt(LocalDateTime.now());users.insert(u);return u.getId();
    }
    MockHttpSession login(String name) throws Exception {
        return(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",name).param("password","TestPassword@123"))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    String raw(String... text) throws Exception {
        var lines=new ArrayList<Map<String,Object>>();for(int i=0;i<text.length;i++)lines.add(Map.of("begin_time",500+i*3000,"end_time",2500+i*3000,"text",text[i]));
        return json.writeValueAsString(Map.of("transcripts",List.of(Map.of("channel_id",0,"sentences",lines))));
    }
    NoteModelClient.Result validNote(List<Segment> source) throws Exception {
        return new NoteModelClient.Result(json.writeValueAsString(Map.of("chapters",List.of(Map.of("title","二分查找","summary","课程讲解了搜索步骤","points",List.of("使用有序数组"),"sourceSegmentIds",source.stream().map(s->s.id).toList())))),100,50);
    }
    OutboxEvent event(long taskId){
        var task=tasks.selectById(taskId);
        return outbox.selectList(Wrappers.<OutboxEvent>query().eq("task_id",taskId).eq("run_no",task.runNo).eq("status","PENDING").orderByDesc("created_at").last("LIMIT 1")).getFirst();
    }
    void step(long id){var e=event(id);worker.process(e.payloadJson);outbox.sent(e.eventId,LocalDateTime.now());}
    ProcessingTaskView drain(long id){
        for(int i=0;i<70;i++){
            var task=processing.get(ownerId,id);if("SUCCEEDED".equals(task.status()))return task;
            assertThat(task.status()).isNotEqualTo("FAILED");step(id);
        }
        throw new AssertionError("Pipeline did not finish");
    }
    long submit(){return processing.submit(ownerId,videoId,2).task().id();}

    @Test void completeWorkflowPersistsStableSubtitlesAndValidatedNotes() {
        long id=submit();var done=drain(id);
        assertThat(done.pipelineVersion()).isEqualTo(2);assertThat(done.steps()).hasSize(6);
        assertThat(segments.selectCount(Wrappers.emptyWrapper())).isEqualTo(3);
        assertThat(notes.selectCount(Wrappers.emptyWrapper())).isEqualTo(1);
        assertThat(read.transcript(ownerId,videoId).get("ready")).isEqualTo(true);
        var sourceIds=segments.selectList(Wrappers.emptyWrapper()).stream().map(s->s.id).toList();
        var duplicate=processing.submit(ownerId,videoId,2);
        assertThat(duplicate.created()).isFalse();assertThat(duplicate.task().id()).isEqualTo(id);
        worker.process(outbox.selectList(Wrappers.emptyWrapper()).getFirst().payloadJson);
        assertThat(segments.selectList(Wrappers.emptyWrapper()).stream().map(s->s.id)).containsExactlyElementsOf(sourceIds);
        verify(cloud,times(1)).submit(anyString(),any());verify(model,times(1)).generate(any(),anyList(),anyBoolean());
    }

    @Test void normalExternalPollingBeyondThreeDoesNotConsumeFailureBudget() throws Exception {
        var polls=new AtomicInteger();
        when(cloud.poll(anyString(),any())).thenAnswer(call->json.readTree(polls.incrementAndGet()<=4?"{\"output\":{\"task_status\":\"RUNNING\"}}":"{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{\"transcription_url\":\"https://unit.oss.aliyuncs.com/r.json\",\"subtask_status\":\"SUCCEEDED\"}]}}"));
        long id=submit();var done=drain(id);
        var poll=done.steps().stream().filter(s->s.stage().equals("ASR_POLL")).findFirst().orElseThrow();
        assertThat(poll.pollCount()).isEqualTo(5);assertThat(poll.failureCount()).isZero();
        verify(cloud,times(1)).submit(anyString(),any());
        assertThat(outbox.selectCount(Wrappers.<OutboxEvent>query().eq("stage","ASR_POLL"))).isEqualTo(5);
    }

    @Test void duplicateAndStalePollWakeupsCannotQueryAgain() throws Exception {
        when(cloud.poll(anyString(),any())).thenReturn(json.readTree("{\"output\":{\"task_status\":\"PENDING\"}}"));
        long id=submit();step(id);step(id);step(id);
        var old=event(id);worker.process(old.payloadJson);
        var next=event(id);assertThat(next.dispatchSeq).isEqualTo(1);
        worker.process(old.payloadJson);worker.deadLetter(old.payloadJson);
        verify(cloud,times(1)).poll(anyString(),any());
        assertThat(processing.get(ownerId,id).status()).isEqualTo("WAITING_EXTERNAL");
        assertThat(steps.current(id,1).stream().filter(s->s.stage.equals("ASR_POLL")).findFirst().orElseThrow().pollCount).isEqualTo(1);
    }

    @Test void uncertainSubmissionRequiresExplicitConfirmationAndNeverAutoResends() {
        doThrow(new LearningFailure("ASR_SUBMISSION_UNKNOWN","结果待确认",false,true)).when(cloud).submit(anyString(),any());
        long id=submit();step(id);step(id);var e=event(id);
        assertThatThrownBy(()->worker.process(e.payloadJson)).isInstanceOf(ProcessingFailures.Permanent.class);worker.deadLetter(e.payloadJson);
        var failed=processing.get(ownerId,id);assertThat(failed.canRetry()).isFalse();assertThat(failed.canResubmitUncertain()).isTrue();
        assertThatThrownBy(()->processing.retry(ownerId,id,1)).isInstanceOf(ApiException.class);
        verify(cloud,times(1)).submit(anyString(),any());
        processing.retry(ownerId,id,1,true);
        var again=event(id);assertThat(again.stage).isEqualTo("ASR_SUBMIT");
        assertThatThrownBy(()->worker.process(again.payloadJson)).isInstanceOf(ProcessingFailures.Permanent.class);worker.deadLetter(again.payloadJson);
        assertThat(jobs.selectCount(Wrappers.emptyWrapper())).isEqualTo(2);
        assertThat(processing.get(ownerId,id).canResubmitUncertain()).isFalse();
        verify(cloud,times(2)).submit(anyString(),any());
    }

    @Test void noteFailureRecoveryReusesAudioRemoteTaskTranscriptAndEarlierWindows() throws Exception {
        when(cloud.result(anyString())).thenReturn(raw("数组".repeat(70),"中间".repeat(70),"减半".repeat(70)));
        var calls=new AtomicInteger();
        doAnswer(call->{if(calls.incrementAndGet()==2)throw new LearningFailure("NOTE_SERVICE_UNAVAILABLE","暂时不可用",true);return validNote(call.getArgument(1));})
            .when(model).generate(any(),anyList(),anyBoolean());
        long id=submit();step(id);step(id);step(id);step(id);step(id);step(id);
        var ids=segments.selectList(Wrappers.emptyWrapper()).stream().map(s->s.id).toList();
        var failedEvent=event(id);
        assertThatThrownBy(()->worker.process(failedEvent.payloadJson)).isInstanceOf(ProcessingFailures.Retryable.class);worker.deadLetter(failedEvent.payloadJson);
        assertThat(notes.selectCount(Wrappers.emptyWrapper())).isEqualTo(1);
        processing.retry(ownerId,id,1);var resumed=event(id);
        assertThat(resumed.stage).isEqualTo("NOTE_BUILD");assertThat(resumed.partId).isEqualTo(2);
        drain(id);
        assertThat(notes.selectCount(Wrappers.emptyWrapper())).isEqualTo(3);
        assertThat(segments.selectList(Wrappers.emptyWrapper()).stream().map(s->s.id)).containsExactlyElementsOf(ids);
        verify(extractor,times(1)).extract(any());verify(cloud,times(1)).submit(anyString(),any());verify(cloud,times(1)).poll(anyString(),any());
        worker.deadLetter(failedEvent.payloadJson);assertThat(processing.get(ownerId,id).status()).isEqualTo("SUCCEEDED");
    }

    @Test void invalidModelReferencesAreRejectedWithTwoCallBudget() {
        doReturn(new NoteModelClient.Result("{\"chapters\":[{\"title\":\"伪造引用\",\"summary\":\"错误\",\"points\":[\"错误\"],\"sourceSegmentIds\":[999999]}]}",100,20))
            .when(model).generate(any(),anyList(),anyBoolean());
        long id=submit();step(id);step(id);step(id);step(id);step(id);var e=event(id);
        assertThatThrownBy(()->worker.process(e.payloadJson)).isInstanceOf(ProcessingFailures.Permanent.class);worker.deadLetter(e.payloadJson);
        assertThat(notes.selectCount(Wrappers.emptyWrapper())).isZero();
        assertThat(artifacts.noteCalls(videoId)).isEqualTo(2);
        assertThat(processing.get(ownerId,id).canRetry()).isFalse();
        assertThat(read.transcript(ownerId,videoId).get("ready")).isEqualTo(true);
    }

    @Test void emptySpeechIsNotTurnedIntoInventedNotes() {
        when(cloud.result(anyString())).thenReturn("{\"transcripts\":[{\"sentences\":[]}]}");
        long id=submit();step(id);step(id);step(id);step(id);var e=event(id);
        assertThatThrownBy(()->worker.process(e.payloadJson)).isInstanceOf(ProcessingFailures.Permanent.class);worker.deadLetter(e.payloadJson);
        assertThat(processing.get(ownerId,id).errorCode()).isEqualTo("NO_SPEECH");verifyNoInteractions(model);
        assertThat(read.transcript(ownerId,videoId).get("ready")).isEqualTo(false);
    }

    @Test void knownRemoteTaskSurvivesRetryWithoutAnotherSubmission() {
        doThrow(new LearningFailure("AI_NETWORK_ERROR","网络故障",true)).when(cloud).poll(anyString(),any());
        long id=submit();step(id);step(id);step(id);var e=event(id);
        assertThatThrownBy(()->worker.process(e.payloadJson)).isInstanceOf(ProcessingFailures.Retryable.class);worker.deadLetter(e.payloadJson);
        processing.retry(ownerId,id,1);
        doAnswer(call->json.readTree("{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{\"transcription_url\":\"https://unit.oss.aliyuncs.com/r.json\"}]}}"))
            .when(cloud).poll(anyString(),any());
        drain(id);verify(cloud,times(1)).submit(anyString(),any());
    }

    @Test void nextOutboxFailureRollsBackStageAdvancement() {
        long id=submit();var e=event(id);
        doThrow(new IllegalStateException("injected next event database failure")).when(outbox).insert(any(OutboxEvent.class));
        assertThatThrownBy(()->worker.process(e.payloadJson)).isInstanceOf(IllegalStateException.class);
        assertThat(steps.current(id,1)).hasSize(1);assertThat(steps.current(id,1).getFirst().status).isEqualTo("RUNNING");
        assertThat(tasks.selectById(id).currentStage).isEqualTo("SOURCE_CHECK");verifyNoInteractions(extractor);
    }

    @Test void wrongExecutionTokenCannotAdvanceCurrentRun() {
        long id=submit();
        when(storage.size(anyString(),anyString())).thenAnswer(call->{jdbc.update("UPDATE task_step SET execution_token='replaced' WHERE task_id=?",id);return 100L;});
        step(id);assertThat(steps.current(id,1)).hasSize(1);assertThat(tasks.selectById(id).status).isEqualTo("RUNNING");
    }

    @Test void mediaResourceDeferralDoesNotConsumeFailureRetries() {
        var count=new AtomicInteger();
        doAnswer(call->{
            if(count.incrementAndGet()<3)throw new AudioExtractor.Deferred();
            var a=new Artifact();a.videoId=videoId;a.kind="AUDIO";a.fingerprint=LearningJson.hash("deferred");a.state="READY";a.bucketName="test";a.objectKey="audio.flac";a.sizeBytes=10;
            a.metadataJson="{\"audioOriginMs\":0}";a.createdAt=LocalDateTime.now();a.updatedAt=a.createdAt;return a;
        }).when(extractor).extract(any());
        long id=submit();var done=drain(id);
        var audio=done.steps().stream().filter(s->s.stage().equals("AUDIO_EXTRACT")).findFirst().orElseThrow();
        assertThat(audio.attemptCount()).isEqualTo(1);assertThat(audio.failureCount()).isZero();
    }

    @Test void allReadingAndSubmissionInterfacesEnforceOwnershipAndCsrf() throws Exception {
        long id=submit();drain(id);
        for(String path:List.of("/transcript","/segments","/notes","/subtitles.vtt")){
            mvc.perform(get("/api/videos/"+videoId+path).session(bob)).andExpect(status().isNotFound());
            mvc.perform(get("/api/videos/"+videoId+path)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/videos/"+videoId+"/processing-tasks").session(alice).contentType("application/json").content("{\"pipelineVersion\":2}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/videos/"+videoId+"/processing-tasks").session(bob).with(csrf()).contentType("application/json").content("{\"pipelineVersion\":2}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/videos/"+videoId+"/subtitles.vtt").session(alice)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.startsWith("WEBVTT")));
        mvc.perform(get("/api/videos/"+videoId+"/segments").session(alice).param("atMs","6500")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get("/api/courses/"+courseId+"/processing-tasks").session(alice).param("videoIds",Long.toString(videoId)).param("pipelineVersion","2")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(id));
    }

    @Test void missingConfigurationOnlyBlocksVersionTwoAndOversizedInputFailsEarly() {
        doReturn(false).when(cloud).configured();
        assertThatThrownBy(()->processing.submit(ownerId,videoId,2)).isInstanceOf(ApiException.class);
        assertThat(processing.submit(ownerId,videoId).task().pipelineVersion()).isEqualTo(1);verifyNoInteractions(model);
        doReturn(true).when(cloud).configured();var video=videos.selectById(videoId);video.setDurationMs(3601000L);videos.updateById(video);
        assertThatThrownBy(()->processing.submit(ownerId,videoId,2)).isInstanceOf(ApiException.class);
        assertThat(jobs.selectCount(Wrappers.emptyWrapper())).isZero();
    }

    @Test void generatedNoteResponseSurvivesFinalTransactionFailureWithoutAnotherModelCall() throws Exception {
        when(cloud.result(anyString())).thenReturn(raw("数组".repeat(70),"中间".repeat(70),"减半".repeat(70)));
        long id=submit();step(id);step(id);step(id);step(id);step(id);var e=event(id);
        doThrow(new IllegalStateException("injected note advancement failure")).when(outbox).insert(any(OutboxEvent.class));
        assertThatThrownBy(()->worker.process(e.payloadJson)).isInstanceOf(IllegalStateException.class);
        assertThat(notes.selectCount(Wrappers.emptyWrapper())).isZero();
        assertThat(artifacts.noteCalls(videoId)).isEqualTo(1);
        verify(storage,times(1)).remove(anyString(),anyString());
        reset(outbox);
        jdbc.update("UPDATE processing_task SET started_at=? WHERE id=?",LocalDateTime.now(ZoneOffset.UTC).minusMinutes(11),id);
        processing.retry(ownerId,id,1);
        drain(id);
        assertThat(notes.selectCount(Wrappers.emptyWrapper())).isEqualTo(3);
        assertThat(artifacts.noteCalls(videoId)).isEqualTo(3);
        verify(model,times(3)).generate(any(),anyList(),anyBoolean());
        verify(cloud,times(1)).submit(anyString(),any());
    }

    @Test void explicitlyFailedRemoteTaskCanBeReplacedByManualRetry() throws Exception {
        doReturn("remote-first","remote-second").when(cloud).submit(anyString(),any());
        doReturn(json.readTree("{\"output\":{\"task_status\":\"FAILED\"}}")).when(cloud).poll(anyString(),any());
        long id=submit();step(id);step(id);step(id);var e=event(id);
        assertThatThrownBy(()->worker.process(e.payloadJson)).isInstanceOf(ProcessingFailures.Permanent.class);worker.deadLetter(e.payloadJson);
        processing.retry(ownerId,id,1);
        assertThat(event(id).stage).isEqualTo("ASR_SUBMIT");
        doReturn(json.readTree("{\"output\":{\"task_status\":\"SUCCEEDED\",\"results\":[{\"transcription_url\":\"https://unit.oss.aliyuncs.com/r.json\"}]}}"))
            .when(cloud).poll(anyString(),any());
        drain(id);assertThat(jobs.selectCount(Wrappers.emptyWrapper())).isEqualTo(2);
        verify(cloud,times(2)).submit(anyString(),any());
    }

    @Test void webVttEscapesTranscriptMarkupAndCannotInjectNewCues() throws Exception {
        when(cloud.result(anyString())).thenReturn(raw("<b>课程</b> & -->\\n新的内容"));
        long id=submit();drain(id);
        String vtt=read.vtt(ownerId,videoId,null);
        assertThat(vtt).contains("&lt;b&gt;课程&lt;/b&gt;").contains("&amp;").contains("--&gt;");
        assertThat(vtt).doesNotContain("<b>");
    }

}
