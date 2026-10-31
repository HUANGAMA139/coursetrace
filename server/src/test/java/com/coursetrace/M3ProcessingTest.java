package com.coursetrace;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coursetrace.auth.*;
import com.coursetrace.course.*;
import com.coursetrace.video.*;
import com.coursetrace.processing.*;
import com.coursetrace.outbox.*;
import com.coursetrace.storage.ObjectStorage;
import com.coursetrace.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:coursetrace_m3;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class M3ProcessingTest {
    @Autowired ProcessingService service;
    @Autowired ProcessingWorker worker;
    @MockitoSpyBean ProcessingMapper tasks;
    @Autowired TaskStepMapper steps;
    @Autowired OutboxService delivery;
    @Autowired ProcessingProperties properties;
    @MockitoSpyBean OutboxMapper outbox;
    @MockitoBean ObjectStorage storage;
    @Autowired CourseVideoMapper videos;
    @Autowired CourseMapper courses;
    @Autowired AppUserMapper users;
    @Autowired PasswordEncoder passwords;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    long ownerId,otherOwnerId,courseId,videoId;
    MockHttpSession alice,bob;

    @BeforeEach void prepare() throws Exception {
        outbox.delete(Wrappers.emptyWrapper()); steps.delete(Wrappers.emptyWrapper()); tasks.delete(Wrappers.emptyWrapper());
        videos.delete(Wrappers.emptyWrapper()); courses.delete(Wrappers.emptyWrapper());
        ownerId=user("m3alice"); otherOwnerId=user("m3bob");
        alice=login("m3alice"); bob=login("m3bob");
        var course=new Course(); course.setOwnerId(ownerId); course.setTitle("异步测试课程"); course.setDescription("");
        course.setCreatedAt(LocalDateTime.now()); course.setUpdatedAt(LocalDateTime.now()); courses.insert(course); courseId=course.getId();
        videoId=video(courseId);
        properties.setMaxPublishAttempts(20);
        doReturn(100L).when(storage).size(anyString(),anyString());
    }

    private long user(String name) {
        var existing=users.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername,name));
        if (existing!=null) return existing.getId();
        var user=new AppUser(); user.setUsername(name); user.setDisplayName(name); user.setPasswordHash(passwords.encode("TestPassword@123"));
        user.setCreatedAt(LocalDateTime.now()); users.insert(user); return user.getId();
    }
    private long video(long course) {
        var v=new CourseVideo(); v.setCourseId(course); v.setTitle("样片"); v.setOriginalFilename("sample.mp4");
        v.setBucketName("test-videos"); v.setObjectKey(UUID.randomUUID()+".mp4"); v.setSizeBytes(100L); v.setContentType("video/mp4");
        v.setDurationMs(1000L); v.setWidth(640); v.setHeight(360); v.setVideoCodec("h264"); v.setCreatedAt(LocalDateTime.now()); videos.insert(v); return v.getId();
    }
    private MockHttpSession login(String name) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).param("username",name).param("password","TestPassword@123"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    private OutboxEvent event() { return outbox.selectList(Wrappers.<OutboxEvent>query().orderByDesc("run_no")).getFirst(); }

    @Test void submissionIsAtomicAndRepeatedRequestsReturnSameTask() {
        var first=service.submit(ownerId,videoId); var again=service.submit(ownerId,videoId);
        assertThat(first.created()).isTrue(); assertThat(again.created()).isFalse();
        assertThat(again.task().id()).isEqualTo(first.task().id());
        assertThat(tasks.selectCount(Wrappers.emptyWrapper())).isEqualTo(1);
        assertThat(steps.selectCount(Wrappers.emptyWrapper())).isEqualTo(1);
        assertThat(outbox.selectCount(Wrappers.emptyWrapper())).isEqualTo(1);
        assertThat(first.task().status()).isEqualTo("PENDING"); verifyNoInteractions(storage);
    }

    @Test void outboxInsertFailureRollsBackTaskAndStep() {
        doThrow(new IllegalStateException("injected outbox failure")).when(outbox).insert(any(OutboxEvent.class));
        assertThatThrownBy(() -> service.submit(ownerId,videoId)).isInstanceOf(IllegalStateException.class);
        assertThat(tasks.selectCount(Wrappers.emptyWrapper())).isZero();
        assertThat(steps.selectCount(Wrappers.emptyWrapper())).isZero();
        assertThat(outbox.selectCount(Wrappers.emptyWrapper())).isZero();
    }

    @Test void concurrentSubmissionCreatesOneEvent() throws Exception {
        try (var pool=Executors.newFixedThreadPool(4)) {
            var gate=new CountDownLatch(1);
            var futures=new ArrayList<Future<Long>>();
            for (int i=0;i<4;i++) futures.add(pool.submit(() -> { gate.await(); return service.submit(ownerId,videoId).task().id(); }));
            gate.countDown();
            var ids=new HashSet<Long>();
            for (var future:futures) ids.add(future.get(10,TimeUnit.SECONDS));
            assertThat(ids).hasSize(1); assertThat(outbox.selectCount(Wrappers.emptyWrapper())).isEqualTo(1);
        }
    }

    @Test void completedDuplicateDoesNotReadStorageAgain() {
        var task=service.submit(ownerId,videoId).task(); var e=event();
        worker.process(e.payloadJson); worker.process(e.payloadJson);
        var done=service.get(ownerId,task.id());
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        assertThat(done.steps().getFirst().attemptCount()).isEqualTo(1);
        assertThat(done.steps().getFirst().result().path("actualSizeBytes").asLong()).isEqualTo(100);
        verify(storage,times(1)).size(anyString(),anyString());
        worker.deadLetter(e.payloadJson);
        assertThat(service.get(ownerId,task.id()).status()).isEqualTo("SUCCEEDED");
    }

    @Test void transientFailuresHaveOneSharedThreeAttemptBudgetAndCanBeRetried() {
        var task=service.submit(ownerId,videoId).task(); var old=event();
        when(storage.size(anyString(),anyString())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"STORAGE_UNAVAILABLE","unavailable"));
        for (int i=0;i<3;i++) assertThatThrownBy(() -> worker.process(old.payloadJson)).isInstanceOf(ProcessingFailures.Retryable.class);
        assertThatThrownBy(() -> worker.process(old.payloadJson)).isInstanceOf(ProcessingFailures.Permanent.class);
        verify(storage,times(3)).size(anyString(),anyString());
        worker.deadLetter(old.payloadJson);
        var failed=service.get(ownerId,task.id()); assertThat(failed.status()).isEqualTo("FAILED"); assertThat(failed.canRetry()).isTrue();
        var next=service.retry(ownerId,task.id(),1);
        assertThat(next.runNo()).isEqualTo(2); assertThat(outbox.selectById(old.eventId).status).isEqualTo("CANCELLED");
        worker.process(old.payloadJson); worker.deadLetter(old.payloadJson);
        assertThat(service.get(ownerId,task.id()).status()).isEqualTo("PENDING");
        doReturn(100L).when(storage).size(anyString(),anyString());
        worker.process(event().payloadJson);
        assertThat(service.get(ownerId,task.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(steps.selectCount(Wrappers.emptyWrapper())).isEqualTo(2);
        assertThatThrownBy(() -> service.retry(ownerId,task.id(),1)).isInstanceOf(ApiException.class);
    }

    @Test void missingSourceIsPermanentAndSizeMismatchIsNotSuccess() {
        var task=service.submit(ownerId,videoId).task(); var e=event();
        when(storage.size(anyString(),anyString())).thenThrow(new ApiException(HttpStatus.NOT_FOUND,"VIDEO_FILE_NOT_FOUND","missing"));
        assertThatThrownBy(() -> worker.process(e.payloadJson)).isInstanceOf(ProcessingFailures.Permanent.class);
        worker.deadLetter(e.payloadJson);
        assertThat(service.get(ownerId,task.id()).errorCode()).isEqualTo("SOURCE_MISSING");
        service.retry(ownerId,task.id(),1);
        doReturn(101L).when(storage).size(anyString(),anyString());
        assertThatThrownBy(() -> worker.process(event().payloadJson)).isInstanceOf(ProcessingFailures.Permanent.class);
        worker.deadLetter(event().payloadJson);
        assertThat(service.get(ownerId,task.id()).errorCode()).isEqualTo("SOURCE_SIZE_MISMATCH");
    }

    @Test void publishFailureBackoffIsBoundedAndExhaustionFailsOnlyWaitingTask() {
        var task=service.submit(ownerId,videoId).task(); var e=event(); properties.setMaxPublishAttempts(2);
        delivery.publishFailed(e);
        var waiting=outbox.selectById(e.eventId);
        assertThat(waiting.status).isEqualTo("PENDING"); assertThat(waiting.publishAttempts).isEqualTo(1);
        assertThat(waiting.nextAttemptAt).isAfter(waiting.createdAt);
        delivery.publishFailed(e);
        assertThat(outbox.selectById(e.eventId).status).isEqualTo("DEAD");
        assertThat(service.get(ownerId,task.id()).errorCode()).isEqualTo("TASK_DELIVERY_FAILED");
        service.retry(ownerId,task.id(),1);
        delivery.publishFailed(e);
        assertThat(service.get(ownerId,task.id()).status()).isEqualTo("PENDING");
    }

    @Test void activeTaskCannotBeRestartedButOldWorkerCannotOverwriteManualRecovery() throws Exception {
        var task=service.submit(ownerId,videoId).task(); var old=event();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(storage.size(anyString(),anyString())).thenAnswer(call -> {
            entered.countDown(); if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test timed out"); return 100L;
        });
        try (var pool=Executors.newSingleThreadExecutor()) {
            var running=pool.submit(() -> worker.process(old.payloadJson));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.retry(ownerId,task.id(),1)).isInstanceOf(ApiException.class);
                worker.process(old.payloadJson); // A duplicate of a busy phase must be harmless.
                jdbc.update("UPDATE processing_task SET started_at=? WHERE id=?",LocalDateTime.now(ZoneOffset.UTC).minusMinutes(11),task.id());
                assertThat(service.get(ownerId,task.id()).canRetry()).isTrue();
                assertThat(service.retry(ownerId,task.id(),1).runNo()).isEqualTo(2);
            } finally { release.countDown(); }
            running.get(5,TimeUnit.SECONDS);
        }
        assertThat(service.get(ownerId,task.id()).status()).isEqualTo("PENDING");
        doReturn(100L).when(storage).size(anyString(),anyString()); worker.process(event().payloadJson);
        assertThat(service.get(ownerId,task.id()).status()).isEqualTo("SUCCEEDED");
    }

    @Test void executionTokenPreventsStaleWriteWithinSameRun() {
        var task=service.submit(ownerId,videoId).task();
        when(storage.size(anyString(),anyString())).thenAnswer(call -> {
            jdbc.update("UPDATE task_step SET execution_token='replaced-token' WHERE task_id=?",task.id());
            return 100L;
        });
        worker.process(event().payloadJson);
        assertThat(service.get(ownerId,task.id()).status()).isEqualTo("RUNNING");
        assertThat(service.get(ownerId,task.id()).steps().getFirst().result()).isNull();
    }

    @Test void malformedOrUnboundMessagesDoNotChangeTasks() throws Exception {
        var task=service.submit(ownerId,videoId).task();
        assertThatThrownBy(() -> worker.process("{bad")).isInstanceOf(ProcessingFailures.InvalidEvent.class);
        var unknown=json.writeValueAsString(new ProcessingEvent(1,UUID.randomUUID().toString(),task.id(),1,1,"SOURCE_CHECK",0));
        assertThatThrownBy(() -> worker.process(unknown)).isInstanceOf(ProcessingFailures.InvalidEvent.class);
        worker.deadLetter(unknown); worker.deadLetter("{bad");
        assertThat(service.get(ownerId,task.id()).status()).isEqualTo("PENDING"); verifyNoInteractions(storage);
    }

    @Test void httpOwnershipCsrfSnapshotsAndRepeatSubmission() throws Exception {
        var path="/api/videos/"+videoId+"/processing-tasks";
        mvc.perform(post(path).session(alice)).andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(path).session(bob).with(csrf())).andExpect(status().isNotFound());
        var response=mvc.perform(post(path).session(alice).with(csrf())).andExpect(status().isAccepted()).andReturn();
        var id=json.readTree(response.getResponse().getContentAsString()).path("data").path("id").asLong();
        mvc.perform(post(path).session(alice).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id));
        mvc.perform(get("/api/processing-tasks/"+id).session(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/processing-tasks/"+id)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/processing-tasks/"+id+"/retry").session(bob).with(csrf()).contentType("application/json").content("{\"expectedRunNo\":1}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/processing-tasks/"+id+"/retry").session(alice).contentType("application/json").content("{\"expectedRunNo\":1}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/courses/"+courseId+"/processing-tasks").session(alice).param("videoIds",Long.toString(videoId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(id))
                .andExpect(jsonPath("$.data[0].steps[0].attemptCount").value(0))
                .andExpect(jsonPath("$.data[0].steps[0].executionToken").doesNotExist());
        mvc.perform(get("/api/courses/"+courseId+"/processing-tasks").session(bob).param("videoIds",Long.toString(videoId))).andExpect(status().isNotFound());
        mvc.perform(get("/api/courses/"+courseId+"/processing-tasks").session(alice).param("videoIds",videoId+",999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/courses/"+courseId+"/processing-tasks").session(alice).param("videoIds","-1")).andExpect(status().isBadRequest());
    }

    @Test void waitingAndSuccessfulTasksCannotStartAnotherRun() {
        var task=service.submit(ownerId,videoId).task();
        assertThatThrownBy(() -> service.retry(ownerId,task.id(),1)).isInstanceOf(ApiException.class);
        worker.process(event().payloadJson);
        assertThatThrownBy(() -> service.retry(ownerId,task.id(),1)).isInstanceOf(ApiException.class);
    }

    @Test void dltDatabaseFailureRollsBackFinalStateAndPropagates() {
        var task=service.submit(ownerId,videoId).task(); var e=event();
        when(storage.size(anyString(),anyString())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"STORAGE_UNAVAILABLE","unavailable"));
        assertThatThrownBy(() -> worker.process(e.payloadJson)).isInstanceOf(ProcessingFailures.Retryable.class);
        doThrow(new IllegalStateException("injected DLT persistence failure")).when(tasks).save(any(ProcessingTask.class));
        assertThatThrownBy(() -> worker.deadLetter(e.payloadJson)).isInstanceOf(IllegalStateException.class);
        assertThat(service.get(ownerId,task.id()).status()).isEqualTo("RETRY_WAIT");
        assertThat(steps.current(task.id(),1).getFirst().status).isEqualTo("RETRY_WAIT");
    }
}
