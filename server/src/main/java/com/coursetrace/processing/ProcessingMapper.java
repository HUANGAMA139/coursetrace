package com.coursetrace.processing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.coursetrace.video.CourseVideo;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface ProcessingMapper extends BaseMapper<ProcessingTask> {
    @Select("SELECT v.* FROM course_video v JOIN course c ON c.id=v.course_id WHERE v.id=#{videoId} AND c.owner_id=#{ownerId} FOR UPDATE")
    CourseVideo lockOwnedVideo(@Param("ownerId") long ownerId, @Param("videoId") long videoId);

    @Select("SELECT * FROM processing_task WHERE video_id=#{videoId} AND pipeline_version=1")
    ProcessingTask findByVideo(long videoId);

    @Select("SELECT * FROM processing_task WHERE id=#{id} FOR UPDATE")
    ProcessingTask lock(long id);

    @Select("SELECT t.* FROM processing_task t JOIN course_video v ON v.id=t.video_id JOIN course c ON c.id=v.course_id WHERE t.id=#{id} AND c.owner_id=#{ownerId}")
    ProcessingTask owned(@Param("ownerId") long ownerId, @Param("id") long id);

    @Select("<script>SELECT COUNT(*) FROM course_video WHERE course_id=#{courseId} AND id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int countVideos(@Param("courseId") long courseId, @Param("ids") List<Long> ids);

    @Select("<script>SELECT t.* FROM processing_task t JOIN course_video v ON v.id=t.video_id JOIN course c ON c.id=v.course_id WHERE c.owner_id=#{ownerId} AND v.course_id=#{courseId} AND t.pipeline_version=1 AND t.video_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<ProcessingTask> batch(@Param("ownerId") long ownerId, @Param("courseId") long courseId, @Param("ids") List<Long> ids);

    // Explicit assignments also clear nullable fields when beginning a new run.
    @Update("UPDATE processing_task SET parameters_json=#{parametersJson},confirm_resubmit_uncertain=#{confirmResubmitUncertain},run_no=#{runNo},current_stage=#{currentStage},status=#{status},error_code=#{errorCode},error_message=#{errorMessage},updated_at=#{updatedAt},started_at=#{startedAt},finished_at=#{finishedAt} WHERE id=#{id}")
    int save(ProcessingTask task);
    @Select("SELECT * FROM processing_task WHERE video_id=#{videoId} AND pipeline_version=#{version}")
    ProcessingTask findVersion(@Param("videoId") long videoId,@Param("version") int version);
    @Select("<script>SELECT t.* FROM processing_task t JOIN course_video v ON v.id=t.video_id JOIN course c ON c.id=v.course_id WHERE c.owner_id=#{ownerId} AND v.course_id=#{courseId} AND t.pipeline_version=#{version} AND t.video_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    java.util.List<ProcessingTask> batchVersion(@Param("ownerId") long ownerId,@Param("courseId") long courseId,@Param("ids") java.util.List<Long> ids,@Param("version") int version);
}
