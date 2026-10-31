package com.coursetrace.video;

import java.time.Instant;
import java.time.ZoneOffset;

public record VideoView(long id, long courseId, String title, String originalFilename, long sizeBytes,
                        long durationMs, int width, int height, String videoCodec, String audioCodec,
                        Instant createdAt, String contentUrl) {
    public static VideoView from(CourseVideo v) {
        return new VideoView(v.getId(), v.getCourseId(), v.getTitle(), v.getOriginalFilename(), v.getSizeBytes(),
                v.getDurationMs(), v.getWidth(), v.getHeight(), v.getVideoCodec(), v.getAudioCodec(),
                v.getCreatedAt().toInstant(ZoneOffset.UTC), "/api/videos/" + v.getId() + "/content");
    }
}
