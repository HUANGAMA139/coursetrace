package com.coursetrace;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coursetrace.auth.*;
import com.coursetrace.common.ApiException;
import com.coursetrace.course.*;
import com.coursetrace.media.*;
import com.coursetrace.storage.ObjectStorage;
import com.coursetrace.video.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:coursetrace_m2;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.servlet.multipart.max-file-size=2KB",
        "coursetrace.media.temp-directory=target/m2-upload-tests"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class M2VideoApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AppUserMapper users;
    @Autowired CourseMapper courses;
    @Autowired CourseVideoMapper videos;
    @Autowired PasswordEncoder passwords;
    @MockitoBean ObjectStorage storage;
    @MockitoBean MediaProbe probe;
    private static final byte[] DATA = "0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private MockHttpSession alice, bob;
    private long courseId;

    @BeforeEach void prepare() throws Exception {
        videos.delete(Wrappers.emptyWrapper()); courses.delete(Wrappers.emptyWrapper());
        for (String name : new String[]{"videoalice", "videobob"}) {
            if (users.selectCount(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, name)) == 0) {
                var u = new AppUser(); u.setUsername(name); u.setDisplayName(name);
                u.setPasswordHash(passwords.encode("TestPassword@123")); u.setCreatedAt(LocalDateTime.now()); users.insert(u);
            }
        }
        alice = login("videoalice"); bob = login("videobob");
        var c = new Course(); c.setOwnerId(users.selectOne(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, "videoalice")).getId());
        c.setTitle("测试课程"); c.setDescription(""); c.setCreatedAt(LocalDateTime.now()); c.setUpdatedAt(LocalDateTime.now()); courses.insert(c); courseId = c.getId();
        when(probe.inspect(any())).thenReturn(new MediaInfo(12000, 640, 360, "h264", "aac"));
        when(storage.size(anyString(), anyString())).thenReturn((long) DATA.length);
        when(storage.open(anyString(), anyString(), anyLong(), anyLong())).thenAnswer(call -> {
            int start = Math.toIntExact(call.getArgument(2, Long.class)), length = Math.toIntExact(call.getArgument(3, Long.class));
            return new ByteArrayInputStream(Arrays.copyOfRange(DATA, start, start + length));
        });
    }

    private MockHttpSession login(String name) throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf()).param("username", name).param("password", "TestPassword@123"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    private MockMultipartFile file() { return new MockMultipartFile("file", "课程.mp4", "video/mp4", DATA); }

    private long upload() throws Exception {
        var result = mvc.perform(multipart("/api/courses/" + courseId + "/videos").file(file()).session(alice).with(csrf()).param("title", "  第一次课  "))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    @Test void anonymousAndCsrfFailuresNeverReachStorage() throws Exception {
        mvc.perform(get("/api/courses/" + courseId + "/videos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/videos/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/videos/1/content").header("Range", "bytes=0-1")).andExpect(status().isUnauthorized());
        mvc.perform(head("/api/videos/1/content")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/courses/" + courseId + "/videos").file(file()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/courses/" + courseId + "/videos").file(file()).session(alice)).andExpect(status().isForbidden());
        verifyNoInteractions(storage, probe);
    }

    @Test void uploadPersistsSafeMetadataAndCleansTemporaryFile() throws Exception {
        long id = upload();
        mvc.perform(get("/api/videos/" + id).session(alice)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("第一次课"))
                .andExpect(jsonPath("$.data.sizeBytes").value(10))
                .andExpect(jsonPath("$.data.durationMs").value(12000))
                .andExpect(jsonPath("$.data.contentUrl").value("/api/videos/" + id + "/content"))
                .andExpect(jsonPath("$.data.objectKey").doesNotExist()).andExpect(jsonPath("$.data.bucketName").doesNotExist());
        assertThat(videos.selectById(id).getCourseId()).isEqualTo(courseId);
        assertThat(videos.selectById(id).getObjectKey()).startsWith("users/").endsWith("/original.mp4");
        verify(storage).put(anyString(), anyString(), any(Path.class), eq(10L));
        try (var files = Files.list(Path.of("target/m2-upload-tests"))) { assertThat(files.count()).isZero(); }
        mvc.perform(get("/api/video-upload-policy").session(alice)).andExpect(jsonPath("$.data.maxBytes").value(2048));
    }

    @Test void otherUsersCannotUploadListReadOrRangeRead() throws Exception {
        long id = upload(); clearInvocations(storage, probe);
        mvc.perform(multipart("/api/courses/" + courseId + "/videos").file(file()).session(bob).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/courses/" + courseId + "/videos").session(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/videos/" + id).session(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/videos/" + id + "/content").session(bob).header("Range", "bytes=0-2")).andExpect(status().isNotFound());
        mvc.perform(head("/api/videos/" + id + "/content").session(bob)).andExpect(status().isNotFound());
        mvc.perform(get("/api/videos/99999999").session(bob)).andExpect(status().isNotFound());
        verifyNoInteractions(storage, probe);
    }

    @Test void invalidUploadsAreRejectedBeforeStorage() throws Exception {
        mvc.perform(multipart("/api/courses/" + courseId + "/videos").file(new MockMultipartFile("file", "empty.mp4", "video/mp4", new byte[0])).session(alice).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/courses/" + courseId + "/videos").file(new MockMultipartFile("file", "bad.txt", "video/mp4", DATA)).session(alice).with(csrf())).andExpect(status().isUnsupportedMediaType());
        mvc.perform(multipart("/api/courses/" + courseId + "/videos").file(new MockMultipartFile("file", "large.mp4", "video/mp4", new byte[2049])).session(alice).with(csrf())).andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart("/api/courses/" + courseId + "/videos").file(file()).param("title", "x".repeat(121)).session(alice).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/courses/" + courseId + "/videos").session(alice).with(csrf())).andExpect(status().isBadRequest());
        verifyNoInteractions(storage, probe);
    }

    @Test void invalidMediaCannotLeaveVideoRowsOrFiles() throws Exception {
        when(probe.inspect(any())).thenThrow(new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_VIDEO", "损坏文件"));
        mvc.perform(multipart("/api/courses/" + courseId + "/videos").file(file()).session(alice).with(csrf())).andExpect(status().isUnsupportedMediaType());
        assertThat(videos.selectCount(Wrappers.emptyWrapper())).isZero(); verifyNoInteractions(storage);
        try (var files = Files.list(Path.of("target/m2-upload-tests"))) { assertThat(files.count()).isZero(); }
    }

    @Test void storageFailureDoesNotCreateSuccessfulRecord() throws Exception {
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_UNAVAILABLE", "存储暂不可用")).when(storage).put(anyString(), anyString(), any(), anyLong());
        mvc.perform(multipart("/api/courses/" + courseId + "/videos").file(file()).session(alice).with(csrf()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
        assertThat(videos.selectCount(Wrappers.emptyWrapper())).isZero();
        verify(storage).remove(anyString(), anyString());
    }

    @Test void listIsPaginatedAndValidatesBounds() throws Exception {
        upload(); upload(); upload();
        mvc.perform(get("/api/courses/" + courseId + "/videos").session(alice).param("size", "2").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3)).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/courses/" + courseId + "/videos").session(alice).param("size", "51")).andExpect(status().isBadRequest());
    }

    @Test void contentSupportsRangesHeadAndFullResponses() throws Exception {
        long id = upload(); String path = "/api/videos/" + id + "/content";
        mvc.perform(get(path).session(alice)).andExpect(status().isOk()).andExpect(content().bytes(DATA)).andExpect(header().string("Content-Length", "10"));
        for (String[] range : new String[][]{{"bytes=2-5","2345","bytes 2-5/10"},{"bytes=7-","789","bytes 7-9/10"},{"bytes=-3","789","bytes 7-9/10"},{"bytes=8-999","89","bytes 8-9/10"}})
            mvc.perform(get(path).session(alice).header("Range",range[0])).andExpect(status().isPartialContent())
                    .andExpect(content().string(range[1])).andExpect(header().string("Content-Range",range[2]))
                    .andExpect(header().string("Content-Length",String.valueOf(range[1].length())));
        mvc.perform(head(path).session(alice).header("Range","bytes=1-2")).andExpect(status().isOk())
                .andExpect(header().string("Accept-Ranges","bytes")).andExpect(header().string("Content-Length","10")).andExpect(content().bytes(new byte[0]));
        for(String range : new String[]{"bytes=10-","bytes=9-3","bytes=-0","bytes=abc"})
            mvc.perform(get(path).session(alice).header("Range",range)).andExpect(status().is(416)).andExpect(header().string("Content-Range","bytes */10")).andExpect(content().bytes(new byte[0]));
        mvc.perform(get(path).session(alice).header("Range","bytes=0-1,4-5")).andExpect(status().isOk()).andExpect(content().bytes(DATA));
        mvc.perform(get(path).session(alice).header("Range","bytes=0-1").header("If-Range","unknown")).andExpect(status().isOk()).andExpect(content().bytes(DATA));
    }

    @Test void storageOpenFailureReturnsJsonBeforeAnyVideoBytes() throws Exception {
        long id = upload();
        when(storage.open(anyString(),anyString(),anyLong(),anyLong())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"STORAGE_UNAVAILABLE","暂不可用"));
        mvc.perform(get("/api/videos/"+id+"/content").session(alice)).andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/json")).andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
    }
}
