package com.coursetrace.video;

import com.coursetrace.auth.AuthenticatedUser;
import com.coursetrace.common.ApiResponse;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.Map;

@RestController
@Validated
public class VideoController {
    private final VideoService videos;
    public VideoController(VideoService videos) { this.videos = videos; }

    @GetMapping("/api/video-upload-policy")
    ApiResponse<?> policy() { return ApiResponse.ok(Map.of("maxBytes", videos.maxUploadBytes())); }

    @PostMapping(value = "/api/courses/{courseId}/videos", consumes = "multipart/form-data")
    ResponseEntity<?> upload(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Min(1) long courseId,
                             @RequestPart("file") MultipartFile file, @RequestParam(required = false) @Size(max = 120) String title) {
        var video = videos.upload(user.id(), courseId, file, title);
        return ResponseEntity.created(URI.create("/api/videos/" + video.id())).body(ApiResponse.ok(video));
    }

    @GetMapping("/api/courses/{courseId}/videos")
    ApiResponse<?> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Min(1) long courseId,
                       @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
                       @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ApiResponse.ok(videos.list(user.id(), courseId, page, size));
    }

    @GetMapping("/api/videos/{id}")
    ApiResponse<?> get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Min(1) long id) {
        return ApiResponse.ok(VideoView.from(videos.owned(user.id(), id)));
    }
}
