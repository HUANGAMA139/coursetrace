package com.coursetrace.upload;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
@Mapper public interface UploadMapper extends BaseMapper<UploadSession> {
 @Select("SELECT id FROM app_user WHERE id=#{owner} FOR UPDATE") Long lockOwner(long owner);
 @Select("SELECT * FROM video_upload WHERE id=#{id} FOR UPDATE") UploadSession lock(String id);
 @Select("SELECT id FROM course WHERE id=#{course} AND owner_id=#{owner} FOR UPDATE") Long lockCourse(@Param("owner")long owner,@Param("course")long course);
 @Select("SELECT * FROM video_upload WHERE owner_id=#{owner} AND course_id=#{course} AND request_id=#{request}") UploadSession request(@Param("owner")long owner,@Param("course")long course,@Param("request")String request);
 @Select("SELECT * FROM video_upload WHERE owner_id=#{owner} AND course_id=#{course} AND file_sha256=#{sha} AND size_bytes=#{size} AND status IN ('UPLOADING','FINALIZING') AND expires_at>#{now} ORDER BY created_at DESC LIMIT 1") UploadSession resume(@Param("owner")long owner,@Param("course")long course,@Param("sha")String sha,@Param("size")long size,@Param("now")LocalDateTime now);
 @Select("SELECT COUNT(*) FROM video_upload WHERE owner_id=#{owner} AND status IN ('UPLOADING','FINALIZING') AND expires_at>#{now}") int active(@Param("owner")long owner,@Param("now")LocalDateTime now);
 @Update("UPDATE video_upload SET status=#{status},video_id=#{videoId},execution_token=#{executionToken},lease_until=#{leaseUntil},final_key=#{finalKey},last_error=#{lastError},updated_at=#{updatedAt} WHERE id=#{id}") int save(UploadSession session);
 @Select("SELECT * FROM video_upload WHERE (status IN ('COMPLETED','CANCELLED','EXPIRED') OR expires_at<=#{now}) AND (lease_until IS NULL OR lease_until<=#{now}) AND (cleaned_at IS NULL OR cleaned_at<#{again}) ORDER BY COALESCE(cleaned_at,created_at) LIMIT 30") List<UploadSession> cleanupDue(@Param("now")LocalDateTime now,@Param("again")LocalDateTime again);
 @Update("UPDATE video_upload SET cleaned_at=#{now} WHERE id=#{id}") void cleaned(@Param("id")String id,@Param("now")LocalDateTime now);
}
