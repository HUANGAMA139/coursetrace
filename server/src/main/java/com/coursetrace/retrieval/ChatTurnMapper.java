package com.coursetrace.retrieval;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.coursetrace.retrieval.SearchModels.ChatTurn;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
@Mapper public interface ChatTurnMapper extends BaseMapper<ChatTurn> {
 @Select("SELECT id FROM app_user WHERE id=#{owner} FOR UPDATE") Long lockOwner(long owner);
 @Select("SELECT * FROM chat_turn WHERE id=#{id} FOR UPDATE") ChatTurn lock(long id);
 @Select("SELECT * FROM chat_turn WHERE owner_id=#{owner} AND video_id=#{video} AND request_id=#{request}") ChatTurn request(@Param("owner")long owner,@Param("video")long video,@Param("request")String request);
 @Update("UPDATE chat_turn SET status='UNKNOWN',error_code='ANSWER_INTERRUPTED',error_message='上次问答未能确认结果，可以重新提问',updated_at=#{now} WHERE owner_id=#{owner} AND status='RUNNING' AND deadline_at<=#{now}") int expire(@Param("owner")long owner,@Param("now")LocalDateTime now);
 @Select("SELECT COUNT(*) FROM chat_turn WHERE owner_id=#{owner} AND status='RUNNING'") int active(long owner);
 @Select("SELECT COUNT(*) FROM chat_turn WHERE owner_id=#{owner} AND created_at>=#{since}") int recentCount(@Param("owner")long owner,@Param("since")LocalDateTime since);
 @Select("SELECT * FROM chat_turn WHERE owner_id=#{owner} AND video_id=#{video} ORDER BY id DESC LIMIT #{size} OFFSET #{offset}") List<ChatTurn> history(@Param("owner")long owner,@Param("video")long video,@Param("size")int size,@Param("offset")int offset);
 @Select("SELECT COUNT(*) FROM chat_turn WHERE owner_id=#{owner} AND video_id=#{video}") int count(@Param("owner")long owner,@Param("video")long video);
 @Select("SELECT * FROM chat_turn WHERE owner_id=#{owner} AND video_id=#{video} AND index_id=#{indexId} AND status='SUCCEEDED' AND id<#{before} ORDER BY id DESC LIMIT 2") List<ChatTurn> context(@Param("owner")long owner,@Param("video")long video,@Param("indexId")long indexId,@Param("before")long before);
 @Update("UPDATE chat_turn SET deadline_at=#{deadlineAt},status=#{status},answer_json=#{answerJson},context_json=#{contextJson},response_json=#{responseJson},error_code=#{errorCode},error_message=#{errorMessage},embedding_requests=#{embeddingRequests},chat_requests=#{chatRequests},input_tokens=#{inputTokens},output_tokens=#{outputTokens},elapsed_ms=#{elapsedMs},updated_at=#{updatedAt} WHERE id=#{id} AND execution_token=#{executionToken} AND status='RUNNING'") int save(ChatTurn turn);
}
