package com.coursetrace.course;

import java.time.Instant;
import java.time.ZoneOffset;

public record CourseView(long id, String title, String description, Instant createdAt, Instant updatedAt) {
    public static CourseView from(Course course) {
        return new CourseView(course.getId(), course.getTitle(), course.getDescription(),
                course.getCreatedAt().toInstant(ZoneOffset.UTC), course.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }
}
