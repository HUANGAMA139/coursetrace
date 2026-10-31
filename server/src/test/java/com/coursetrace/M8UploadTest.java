package com.coursetrace;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coursetrace.auth.*;
import com.coursetrace.common.ApiException;
import com.coursetrace.course.*;
import com.coursetrace.media.*;
import com.coursetrace.storage.ObjectStorage;
import com.coursetrace.upload.*;
import com.coursetrace.video.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:coursetrace_m8;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","coursetrace.media.temp-directory=target/m8-tests","coursetrace.upload.cleanup-delay-ms=3600000"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class M8UploadTest {
 @Autowired UploadService service;@Autowired UploadMapper sessions;@Autowired UploadPartMapper parts;@Autowired CourseVideoMapper videos;@Autowired CourseMapper courses;@Autowired AppUserMapper users;@Autowired MockMvc mvc;
 @MockitoBean ObjectStorage storage;@MockitoBean MediaProbe probe;
 private final Map<String,byte[]> objects=new ConcurrentHashMap<>();
 private long owner,other,course;private AuthenticatedUser principal;
 private static final int CHUNK=8*1024*1024;
 @BeforeEach void setup()throws Exception{
  parts.delete(Wrappers.emptyWrapper());sessions.delete(Wrappers.emptyWrapper());videos.delete(Wrappers.emptyWrapper());courses.delete(Wrappers.emptyWrapper());objects.clear();
  owner=userId("m8-owner");other=userId("m8-other");principal=new AuthenticatedUser(users.selectById(owner));
  var c=new Course();c.setOwnerId(owner);c.setTitle("分片测试");c.setDescription("");c.setCreatedAt(LocalDateTime.now());c.setUpdatedAt(LocalDateTime.now());courses.insert(c);course=c.getId();
  doAnswer(a->{objects.put(a.getArgument(1),Files.readAllBytes(a.getArgument(2)));return null;}).when(storage).putFile(anyString(),anyString(),any(),anyLong(),anyString());
  doAnswer(a->{var output=new ByteArrayOutputStream();for(String key:(List<String>)a.getArgument(1))output.write(objects.get(key));objects.put(a.getArgument(2),output.toByteArray());return null;}).when(storage).compose(anyString(),anyList(),anyString());
  when(storage.size(anyString(),anyString())).thenAnswer(a->(long)objects.get(a.getArgument(1)).length);
  when(storage.open(anyString(),anyString(),anyLong(),anyLong())).thenAnswer(a->new ByteArrayInputStream(objects.get(a.getArgument(1))));
  when(storage.list(anyString(),anyString())).thenAnswer(a->objects.keySet().stream().filter(k->k.startsWith(a.getArgument(1))).toList());
  doAnswer(a->{objects.remove(a.getArgument(1));return null;}).when(storage).remove(anyString(),anyString());
  when(probe.inspect(any())).thenReturn(new MediaInfo(12000,640,360,"h264","aac"));
 }
 private long userId(String name){var found=users.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername,name));if(found!=null)return found.getId();var u=new AppUser();u.setUsername(name);u.setDisplayName(name);u.setPasswordHash("unused");u.setCreatedAt(LocalDateTime.now());users.insert(u);return u.getId();}
 private String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
 private UploadService.View init(byte[] data)throws Exception{return service.init(owner,course,new UploadController.Init(UUID.randomUUID().toString(),"lesson.mp4","课程",data.length,hash(data)));}
 private void send(UploadService.View s,int number,byte[] bytes)throws Exception{service.part(owner,s.id(),number,hash(bytes),new MockMultipartFile("file","part.bin","application/octet-stream",bytes));}
 private void sendAll(UploadService.View s,byte[] data)throws Exception{for(int n=0;n<s.chunkCount();n++)send(s,n,Arrays.copyOfRange(data,n*CHUNK,Math.min(data.length,(n+1)*CHUNK)));}
 @Test void resumesConfirmedPartsAndCompletesOnlyOnce()throws Exception{
  byte[] data=new byte[CHUNK+17];Arrays.fill(data,(byte)7);var s=init(data);send(s,0,Arrays.copyOfRange(data,0,CHUNK));
  var resumed=init(data);assertThat(resumed.id()).isEqualTo(s.id());assertThat(resumed.parts()).hasSize(1);
  send(s,1,Arrays.copyOfRange(data,CHUNK,data.length));var completed=service.complete(owner,s.id());assertThat(completed.status()).isEqualTo("COMPLETED");assertThat(service.complete(owner,s.id()).videoId()).isEqualTo(completed.videoId());assertThat(videos.selectCount(Wrappers.emptyWrapper())).isEqualTo(1);assertThat(objects.get(sessions.selectById(s.id()).finalKey)).isEqualTo(data);
 }
 @Test void duplicatePartDoesNotOverwriteAndConflictIsRejected()throws Exception{var s=init(new byte[]{1,2});send(s,0,new byte[]{1,2});send(s,0,new byte[]{1,2});assertThat(parts.parts(s.id())).hasSize(1);assertThatThrownBy(()->send(s,0,new byte[]{2,1})).isInstanceOf(ApiException.class).hasMessageContaining("内容不同");}
 @Test void rejectsMissingPartsBadSizeAndChecksum()throws Exception{var s=init(new byte[]{1,2});assertThatThrownBy(()->service.complete(owner,s.id())).hasMessageContaining("分片未完成");assertThatThrownBy(()->send(s,0,new byte[]{1})).hasMessageContaining("大小");assertThatThrownBy(()->service.part(owner,s.id(),0,"0".repeat(64),new MockMultipartFile("file",new byte[]{1,2}))).hasMessageContaining("校验失败");assertThat(parts.parts(s.id())).isEmpty();}
 @Test void rejectsMergedDigestMismatchWithoutCreatingVideo()throws Exception{var s=service.init(owner,course,new UploadController.Init(UUID.randomUUID().toString(),"a.mp4","课程",2,"0".repeat(64)));send(s,0,new byte[]{1,2});assertThatThrownBy(()->service.complete(owner,s.id())).hasMessageContaining("完整文件校验失败");assertThat(videos.selectCount(Wrappers.emptyWrapper())).isZero();assertThat(service.status(owner,s.id()).status()).isEqualTo("UPLOADING");}
 @Test void completionCanRecoverExpiredLease()throws Exception{var data=new byte[]{1,2};var s=init(data);sendAll(s,data);var row=sessions.selectById(s.id());row.status="FINALIZING";row.executionToken="old";row.leaseUntil=LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1);sessions.save(row);assertThat(service.status(owner,s.id()).canComplete()).isTrue();assertThat(service.complete(owner,s.id()).status()).isEqualTo("COMPLETED");}
 @Test void activeCompletionIsReadOnly()throws Exception{var s=init(new byte[]{1});send(s,0,new byte[]{1});var row=sessions.selectById(s.id());row.status="FINALIZING";row.executionToken="active";row.leaseUntil=LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1);sessions.save(row);assertThat(service.complete(owner,s.id()).status()).isEqualTo("FINALIZING");verify(storage,never()).compose(anyString(),anyList(),anyString());}
 @Test void deniesAnotherOwnerOnEveryOperation()throws Exception{var s=init(new byte[]{1});assertThatThrownBy(()->service.status(other,s.id())).hasMessageContaining("无法访问");assertThatThrownBy(()->service.complete(other,s.id())).hasMessageContaining("无法访问");assertThatThrownBy(()->service.cancel(other,s.id())).hasMessageContaining("无法访问");assertThatThrownBy(()->service.part(other,s.id(),0,hash(new byte[]{1}),new MockMultipartFile("file",new byte[]{1}))).hasMessageContaining("无法访问");}
 @Test void cancelledAndExpiredSessionsCannotAcceptParts()throws Exception{var s=init(new byte[]{1});service.cancel(owner,s.id());assertThatThrownBy(()->send(s,0,new byte[]{1})).hasMessageContaining("不可写");var expired=init(new byte[]{2});var row=sessions.selectById(expired.id());row.expiresAt=LocalDateTime.now(ZoneOffset.UTC).minusHours(1);sessions.updateById(row);assertThatThrownBy(()->send(expired,0,new byte[]{2})).hasMessageContaining("不可写");}
 @Test void cleanupPreservesCompletedVideoAndRemovesTemporaryParts()throws Exception{var data=new byte[]{1,2};var s=init(data);sendAll(s,data);service.complete(owner,s.id());String finalKey=sessions.selectById(s.id()).finalKey;service.cleanup();assertThat(objects.keySet()).containsExactly(finalKey);}
 @Test void failedMediaValidationCanBeRetriedWithoutReuploading()throws Exception{var s=init(new byte[]{1});send(s,0,new byte[]{1});when(probe.inspect(any())).thenThrow(new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"MEDIA_UNAVAILABLE","工具暂不可用")).thenReturn(new MediaInfo(1000,640,360,"h264","aac"));assertThatThrownBy(()->service.complete(owner,s.id())).hasMessageContaining("工具暂不可用");assertThat(service.status(owner,s.id()).parts()).hasSize(1);assertThat(service.complete(owner,s.id()).status()).isEqualTo("COMPLETED");}
 @Test void concurrentCompleteCreatesSingleVideo()throws Exception{var s=init(new byte[]{1});send(s,0,new byte[]{1});try(var executor=Executors.newFixedThreadPool(2)){var jobs=executor.invokeAll(List.of(()->{try{return service.complete(owner,s.id()).status();}catch(ApiException busy){return busy.getCode();}},()->{try{return service.complete(owner,s.id()).status();}catch(ApiException busy){return busy.getCode();}}));for(var job:jobs)assertThat(job.get().toString()).isIn("COMPLETED","FINALIZING","MERGE_BUSY");}assertThat(videos.selectCount(Wrappers.emptyWrapper())).isEqualTo(1);}
 @Test void apiRequiresSessionCsrfAndValidatedMetadata()throws Exception{
  mvc.perform(get("/api/uploads/missing")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/courses/"+course+"/uploads").with(user(principal)).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
  mvc.perform(post("/api/courses/"+course+"/uploads").with(user(principal)).with(csrf()).contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
 }
 @Test void expiredInitRequestReturnsExpiredInsteadOfUnwritableSession()throws Exception{var data=new byte[]{1};String request=UUID.randomUUID().toString();var input=new UploadController.Init(request,"a.mp4","课程",1,hash(data));var s=service.init(owner,course,input);var row=sessions.selectById(s.id());row.expiresAt=LocalDateTime.now(ZoneOffset.UTC).minusHours(1);sessions.updateById(row);assertThat(service.init(owner,course,input).status()).isEqualTo("EXPIRED");}
 @Test void lateCompletionCannotOverwriteAnotherExecution()throws Exception{var s=init(new byte[]{1});send(s,0,new byte[]{1});when(probe.inspect(any())).thenAnswer(a->{var row=sessions.selectById(s.id());row.executionToken="new-execution";sessions.save(row);return new MediaInfo(1000,640,360,"h264","aac");});assertThatThrownBy(()->service.complete(owner,s.id())).hasMessageContaining("状态已变化");assertThat(sessions.selectById(s.id()).executionToken).isEqualTo("new-execution");assertThat(sessions.selectById(s.id()).status).isEqualTo("FINALIZING");assertThat(videos.selectCount(Wrappers.emptyWrapper())).isZero();}
}
