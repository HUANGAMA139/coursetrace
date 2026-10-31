package com.coursetrace.upload;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface UploadPartMapper extends BaseMapper<UploadPart> {
 @Select("SELECT * FROM video_upload_part WHERE upload_id=#{id} ORDER BY part_no") List<UploadPart> parts(String id);
 @Select("SELECT * FROM video_upload_part WHERE upload_id=#{id} AND part_no=#{part}") UploadPart part(@Param("id")String id,@Param("part")int part);
 @Select("SELECT COUNT(*) FROM video_upload_part WHERE object_key=#{key}") int referenced(String key);
}
