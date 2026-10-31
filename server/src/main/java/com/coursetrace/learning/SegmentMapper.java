package com.coursetrace.learning;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface SegmentMapper extends BaseMapper<Segment> {
    @Select("SELECT * FROM course_segment WHERE transcript_artifact_id=#{artifactId} ORDER BY ordinal LIMIT 10001")
    List<Segment> transcript(long artifactId);
}
