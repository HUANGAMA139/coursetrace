package com.coursetrace.learning;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
@Mapper
public interface ArtifactMapper extends BaseMapper<Artifact> {
    @Select("SELECT * FROM processing_artifact WHERE video_id=#{videoId} AND kind=#{kind} AND fingerprint=#{fingerprint}")
    Artifact find(@Param("videoId") long videoId,@Param("kind") String kind,@Param("fingerprint") String fingerprint);
    @Select("SELECT * FROM processing_artifact WHERE id=#{id} FOR UPDATE")
    Artifact lock(long id);
    @Select("SELECT COALESCE(SUM(request_count),0) FROM processing_artifact WHERE video_id=#{videoId} AND kind='NOTE'")
    int noteCalls(long videoId);
    @Select("SELECT COUNT(*) FROM processing_artifact WHERE bucket_name=#{bucket} AND object_key=#{key}")
    int references(@Param("bucket") String bucket,@Param("key") String key);
}
