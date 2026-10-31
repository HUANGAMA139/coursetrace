package com.coursetrace.learning;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper
public interface NoteMapper extends BaseMapper<CourseNote> {
    @Select("SELECT * FROM course_note WHERE input_digest=#{digest} ORDER BY chapter_no")
    List<CourseNote> window(String digest);
    @Select("SELECT * FROM course_note WHERE video_id=#{videoId} AND transcript_artifact_id=#{artifactId} AND model=#{model} AND prompt_version=#{prompt} ORDER BY window_no,chapter_no LIMIT 100")
    List<CourseNote> notes(@Param("videoId") long videoId,@Param("artifactId") long artifactId,@Param("model") String model,@Param("prompt") String prompt);
}
