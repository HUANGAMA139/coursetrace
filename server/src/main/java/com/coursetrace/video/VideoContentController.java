package com.coursetrace.video;

import com.coursetrace.auth.AuthenticatedUser;
import com.coursetrace.common.ApiException;
import com.coursetrace.storage.ObjectStorage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.concurrent.Semaphore;

@RestController
@Validated
public class VideoContentController {
    private static final Logger log = LoggerFactory.getLogger(VideoContentController.class);
    private final VideoService videos;
    private final ObjectStorage storage;
    private final Semaphore streams = new Semaphore(4);

    public VideoContentController(VideoService videos, ObjectStorage storage) { this.videos = videos; this.storage = storage; }

    @RequestMapping(value = "/api/videos/{id}/content", method = {RequestMethod.GET, RequestMethod.HEAD})
    void content(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Min(1) long id,
                 HttpServletRequest request, HttpServletResponse response) throws IOException {
        var video = videos.owned(user.id(), id);
        if (!streams.tryAcquire()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLAYBACK_BUSY", "播放连接较多，请稍后重试");
        try {
            if ("HEAD".equals(request.getMethod())) {
                storage.size(video.getBucketName(), video.getObjectKey());
                headers(response, video.getSizeBytes());
                return;
            }
            ByteRange range;
            try {
                // Conditional validators are not implemented; a full response is safe for If-Range.
                range = ByteRange.parse(request.getHeader("If-Range") == null ? request.getHeader("Range") : null, video.getSizeBytes());
            } catch (IllegalArgumentException ex) {
                response.setStatus(416);
                response.setHeader("Content-Range", "bytes */" + video.getSizeBytes());
                response.setHeader("Accept-Ranges", "bytes");
                response.setHeader("Cache-Control", "private, no-store");
                response.setContentLengthLong(0);
                return;
            }
            // Open upstream before committing headers so storage errors remain readable JSON.
            try (var input = storage.open(video.getBucketName(), video.getObjectKey(), range.offset(), range.length())) {
                headers(response, range.length());
                if (range.partial()) {
                    response.setStatus(206);
                    response.setHeader("Content-Range", "bytes " + range.offset() + "-" + (range.offset() + range.length() - 1) + "/" + video.getSizeBytes());
                }
                byte[] buffer = new byte[65536];
                long remaining = range.length();
                var output = response.getOutputStream();
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new IOException("Object ended before declared length");
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            } catch (IOException ex) {
                if (!response.isCommitted()) {
                    response.reset();
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PLAYBACK_INTERRUPTED", "视频读取中断，请重新播放");
                }
                // Never append JSON to an already-started binary response.
                log.debug("Video stream ended early for video {}: {}", id, ex.getClass().getSimpleName());
            }
        } finally { streams.release(); }
    }

    private void headers(HttpServletResponse response, long length) {
        response.setContentType("video/mp4");
        response.setContentLengthLong(length);
        response.setHeader("Accept-Ranges", "bytes");
        response.setHeader("Cache-Control", "private, no-store");
    }
}
