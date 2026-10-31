package com.coursetrace.retrieval;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.coursetrace.retrieval.SearchModels.SearchState;
import org.apache.ibatis.annotations.*;
@Mapper public interface SearchStateMapper extends BaseMapper<SearchState> {
 @Update("UPDATE video_search_state SET active_index_id=#{activeIndexId},building_index_id=#{buildingIndexId},updated_at=#{updatedAt} WHERE video_id=#{videoId}") int save(SearchState state);
}
