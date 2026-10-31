package com.coursetrace.retrieval;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.coursetrace.retrieval.SearchModels.SearchIndex;
import org.apache.ibatis.annotations.*;
@Mapper public interface SearchIndexMapper extends BaseMapper<SearchIndex> {
 @Select("SELECT * FROM video_search_index WHERE video_id=#{videoId} AND request_id=#{requestId}") SearchIndex request(@Param("videoId") long videoId,@Param("requestId") String requestId);
 @Update("UPDATE video_search_index SET status=#{status},chunk_count=#{chunkCount},batch_count=#{batchCount},error_code=#{errorCode},updated_at=#{updatedAt} WHERE id=#{id}") int save(SearchIndex index);
}
