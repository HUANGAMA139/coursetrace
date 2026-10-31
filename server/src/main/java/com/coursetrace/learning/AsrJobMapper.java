package com.coursetrace.learning;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
@Mapper
public interface AsrJobMapper extends BaseMapper<AsrJob> {
    @Select("SELECT * FROM asr_job WHERE audio_artifact_id=#{audioId} AND config_hash=#{hash} ORDER BY submission_no DESC LIMIT 1")
    AsrJob latest(@Param("audioId") long audioId,@Param("hash") String hash);
    @Select("SELECT * FROM asr_job WHERE id=#{id} FOR UPDATE")
    AsrJob lock(long id);
    @Update("UPDATE asr_job SET provider_task_id=#{providerTaskId},status=#{status},updated_at=#{updatedAt},error_code=NULL WHERE id=#{id} AND provider_task_id IS NULL")
    int accepted(AsrJob job);
}
