package com.coursetrace.course;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.coursetrace.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class CourseService {
    private final CourseMapper courses;
    private final Clock clock;

    public CourseService(CourseMapper courses, Clock clock) { this.courses = courses; this.clock = clock; }

    @Transactional
    public CourseView create(long ownerId, CreateCourseRequest request) {
        var course = new Course();
        course.setOwnerId(ownerId);
        course.setTitle(request.title().strip());
        course.setDescription(request.description() == null ? "" : request.description().strip());
        var now = LocalDateTime.now(clock);
        course.setCreatedAt(now);
        course.setUpdatedAt(now);
        courses.insert(course);
        return CourseView.from(course);
    }

    public CoursePage list(long ownerId, int page, int size, String keyword) {
        var query = Wrappers.<Course>lambdaQuery().eq(Course::getOwnerId, ownerId)
                .like(keyword != null && !keyword.isBlank(), Course::getTitle, keyword == null ? null : keyword.strip())
                .orderByDesc(Course::getCreatedAt).orderByDesc(Course::getId);
        var result = courses.selectPage(new Page<>(page, size), query);
        return new CoursePage(result.getRecords().stream().map(CourseView::from).toList(), result.getTotal(), page, size);
    }

    public CourseView get(long ownerId, long id) {
        // Scope the query itself, so guessing an ID cannot expose another student's course.
        var course = courses.selectOne(Wrappers.<Course>lambdaQuery()
                .eq(Course::getId, id).eq(Course::getOwnerId, ownerId));
        if (course == null) throw new ApiException(HttpStatus.NOT_FOUND, "COURSE_NOT_FOUND", "课程不存在或无法访问");
        return CourseView.from(course);
    }

    public record CoursePage(List<CourseView> items, long total, int page, int size) {}
}
