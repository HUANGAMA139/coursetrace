package com.coursetrace.learning;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("course_segment")
public class Segment {
    @TableId(type=IdType.AUTO) public Long id;
    public Long videoId;
    public Long transcriptArtifactId;
    public int ordinal;
    public long beginMs;
    public long endMs;
    public String text;
    public LocalDateTime createdAt;
}
