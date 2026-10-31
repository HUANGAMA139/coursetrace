package com.coursetrace.video;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CourseVideoMapper extends BaseMapper<CourseVideo> {
    @Select("SELECT v.* FROM course_video v JOIN course c ON c.id = v.course_id WHERE v.id = #{id} AND c.owner_id = #{ownerId}")
    CourseVideo findOwned(@Param("ownerId") long ownerId, @Param("id") long id);
}
