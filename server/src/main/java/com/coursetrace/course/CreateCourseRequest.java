package com.coursetrace.course;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCourseRequest(
        @NotBlank(message = "请输入课程名称") @Size(max = 120, message = "课程名称最多 120 个字符") String title,
        @Size(max = 1000, message = "课程简介最多 1000 个字符") String description) {}
