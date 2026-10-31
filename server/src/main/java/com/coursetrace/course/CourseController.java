package com.coursetrace.course;

import com.coursetrace.auth.AuthenticatedUser;
import com.coursetrace.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@Validated
@RestController
@RequestMapping("/api/courses")
public class CourseController {
    private final CourseService courses;

    public CourseController(CourseService courses) { this.courses = courses; }

    @PostMapping
    ResponseEntity<ApiResponse<CourseView>> create(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @Valid @RequestBody CreateCourseRequest request) {
        var course = courses.create(user.id(), request);
        return ResponseEntity.created(URI.create("/api/courses/" + course.id())).body(ApiResponse.ok(course));
    }

    @GetMapping
    ApiResponse<CourseService.CoursePage> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
            @RequestParam(defaultValue = "9") @Min(1) @Max(50) int size,
            @RequestParam(required = false) @Size(max = 120) String keyword) {
        return ApiResponse.ok(courses.list(user.id(), page, size, keyword));
    }

    @GetMapping("/{id}")
    ApiResponse<CourseView> get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable @Min(1) long id) {
        return ApiResponse.ok(courses.get(user.id(), id));
    }
}
