package com.coursetrace.retrieval;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.coursetrace.retrieval.SearchModels.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface ChunkMapper extends BaseMapper<Chunk> {
 @Select("SELECT * FROM subtitle_chunk WHERE index_id=#{indexId} ORDER BY ordinal LIMIT 201") List<Chunk> chunks(long indexId);
 @Insert("INSERT INTO subtitle_chunk_segment(chunk_id,segment_id,ordinal,start_offset,end_offset) VALUES(#{chunkId},#{segmentId},#{ordinal},#{startOffset},#{endOffset})") int source(ChunkSource source);
 @Select("SELECT * FROM subtitle_chunk_segment WHERE chunk_id=#{chunkId} ORDER BY ordinal") List<ChunkSource> sources(long chunkId);
}
