package com.coursetrace.video;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.coursetrace.common.ApiException;
import com.coursetrace.course.CourseService;
import com.coursetrace.media.MediaProbe;
import com.coursetrace.media.MediaProperties;
import com.coursetrace.storage.ObjectStorage;
import com.coursetrace.storage.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;

@Service
public class VideoService {
    private static final Logger log = LoggerFactory.getLogger(VideoService.class);
    private final CourseService courses;
    private final CourseVideoMapper videos;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;
    private final MediaProperties mediaProperties;
    private final MediaProbe probe;
    private final MultipartProperties multipart;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Semaphore uploads = new Semaphore(2);

    public VideoService(CourseService courses, CourseVideoMapper videos, ObjectStorage storage,
                        StorageProperties storageProperties, MediaProperties mediaProperties, MediaProbe probe,
                        MultipartProperties multipart, TransactionTemplate transaction, Clock clock) {
        this.courses = courses; this.videos = videos; this.storage = storage;
        this.storageProperties = storageProperties; this.mediaProperties = mediaProperties; this.probe = probe;
        this.multipart = multipart; this.transaction = transaction; this.clock = clock;
    }

    public long maxUploadBytes() { return multipart.getMaxFileSize().toBytes(); }

    public VideoView upload(long ownerId, long courseId, MultipartFile file, String requestedTitle) {
        courses.get(ownerId, courseId);
        if (file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "请选择非空的视频文件");
        if (file.getSize() > maxUploadBytes()) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "视频超过允许的文件大小");
        String original = cleanFilename(file.getOriginalFilename());
        if (!original.toLowerCase(Locale.ROOT).endsWith(".mp4")) throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "INVALID_VIDEO", "仅支持 MP4 视频文件");
        String title = requestedTitle == null || requestedTitle.isBlank() ? original.substring(0, original.length() - 4).strip() : requestedTitle.strip();
        if (title.isBlank() || title.length() > 120) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TITLE", "视频标题需为 1～120 个字符");
        if (!uploads.tryAcquire()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_BUSY", "上传任务较多，请稍后重试");
        Path temporary = null;
        String key = null;
        boolean committed = false;
        try {
            Path directory = Path.of(mediaProperties.tempDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "video-", ".mp4");
            file.transferTo(temporary);
            var info = probe.inspect(temporary);
            key = "users/" + ownerId + "/courses/" + courseId + "/videos/" + UUID.randomUUID() + "/original.mp4";
            storage.put(storageProperties.bucket(), key, temporary, file.getSize());
            var video = new CourseVideo();
            video.setCourseId(courseId); video.setTitle(title); video.setOriginalFilename(original);
            video.setBucketName(storageProperties.bucket()); video.setObjectKey(key);
            video.setSizeBytes(file.getSize()); video.setContentType("video/mp4"); video.setDurationMs(info.durationMs());
            video.setWidth(info.width()); video.setHeight(info.height()); video.setVideoCodec(info.videoCodec());
            video.setAudioCodec(info.audioCodec()); video.setCreatedAt(LocalDateTime.now(clock));
            // External I/O is complete before starting the short database transaction.
            transaction.executeWithoutResult(status -> { courses.get(ownerId, courseId); videos.insert(video); });
            committed = true;
            return VideoView.from(video);
        } catch (IOException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "UPLOAD_IO_ERROR", "无法暂存视频，请检查磁盘空间后重试");
        } finally {
            // Compensate even for a timed-out write: the object may already exist remotely.
            if (!committed && key != null) try { storage.remove(storageProperties.bucket(), key); }
            catch (RuntimeException ex) { log.warn("Upload cleanup required: bucket={}, key={}", storageProperties.bucket(), key); }
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException ex) { log.warn("Temporary video cleanup required: {}", temporary.getFileName()); }
            uploads.release();
        }
    }

    private String cleanFilename(String input) {
        String value = input == null ? "" : input.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (value.isBlank() || value.length() > 255) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILENAME", "文件名需为 1～255 个字符");
        return value;
    }

    public VideoPage list(long ownerId, long courseId, int page, int size) {
        courses.get(ownerId, courseId);
        var result = videos.selectPage(new Page<>(page, size), Wrappers.<CourseVideo>lambdaQuery()
                .eq(CourseVideo::getCourseId, courseId).orderByDesc(CourseVideo::getCreatedAt).orderByDesc(CourseVideo::getId));
        return new VideoPage(result.getRecords().stream().map(VideoView::from).toList(), result.getTotal(), page, size);
    }

    // Called inside the upload-session completion transaction: video and session commit together.
    public CourseVideo saveUploaded(long ownerId,long courseId,String title,String filename,String key,long size,com.coursetrace.media.MediaInfo info){
        courses.get(ownerId,courseId);
        var video=new CourseVideo();video.setCourseId(courseId);video.setTitle(title);video.setOriginalFilename(filename);
        video.setBucketName(storageProperties.bucket());video.setObjectKey(key);video.setSizeBytes(size);video.setContentType("video/mp4");
        video.setDurationMs(info.durationMs());video.setWidth(info.width());video.setHeight(info.height());video.setVideoCodec(info.videoCodec());video.setAudioCodec(info.audioCodec());video.setCreatedAt(LocalDateTime.now(clock));
        videos.insert(video);return video;
    }

    public CourseVideo owned(long ownerId, long videoId) {
        var video = videos.findOwned(ownerId, videoId);
        if (video == null) throw new ApiException(HttpStatus.NOT_FOUND, "VIDEO_NOT_FOUND", "视频不存在或无法访问");
        return video;
    }

    public record VideoPage(List<VideoView> items, long total, int page, int size) {}
}
