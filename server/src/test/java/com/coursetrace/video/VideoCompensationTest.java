package com.coursetrace.video;

import com.coursetrace.course.CourseService;
import com.coursetrace.media.*;
import com.coursetrace.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.time.Clock;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoCompensationTest {
    @TempDir Path temporary;

    @Test void databaseRollbackTriggersObjectDeletionAndPreservesOriginalError() throws Exception {
        var courses = mock(CourseService.class); var videos = mock(CourseVideoMapper.class);
        var store = mock(ObjectStorage.class); var probe = mock(MediaProbe.class);
        var manager = mock(PlatformTransactionManager.class);
        var status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        when(probe.inspect(any())).thenReturn(new MediaInfo(1000,640,360,"h264",null));
        doThrow(new DataIntegrityViolationException("Database insert failed")).when(videos).insert(any(CourseVideo.class));
        // A failed compensation must not replace the database exception.
        doThrow(new IllegalStateException("Storage unavailable during cleanup")).when(store).remove(anyString(),anyString());
        var service = new VideoService(courses,videos,store,new StorageProperties("http://127.0.0.1:9000","test","test","test-videos"),
                new MediaProperties("unused",temporary.toString(),15),probe,new MultipartProperties(),new TransactionTemplate(manager),Clock.systemUTC());
        assertThatThrownBy(() -> service.upload(1,2,new MockMultipartFile("file","sample.mp4","video/mp4",new byte[]{1,2,3}),"sample"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessage("Database insert failed");
        verify(manager).rollback(status); verify(store).remove(eq("test-videos"),startsWith("users/1/courses/2/"));
        try(var files=Files.list(temporary)){ assertThat(files.count()).isZero(); }
    }
}
