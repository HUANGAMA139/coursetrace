package com.coursetrace.learning;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("course_note")
public class CourseNote {
    @TableId(type=IdType.AUTO) public Long id;
    public Long videoId;
    public Long transcriptArtifactId;
    public int windowNo;
    public int chapterNo;
    public String inputDigest;
    public String title;
    public String summary;
    public String pointsJson;
    public String sourceSegmentIdsJson;
    public long beginMs;
    public long endMs;
    public String model;
    public String promptVersion;
    public LocalDateTime createdAt;
}
