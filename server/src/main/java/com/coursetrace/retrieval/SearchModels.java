package com.coursetrace.retrieval;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
public final class SearchModels {
 private SearchModels(){}
 @TableName("video_search_index") public static class SearchIndex {
  @TableId(type=IdType.AUTO) public Long id;
  public Long videoId,ownerId,courseId,transcriptArtifactId,taskId;
  public String fingerprint,configJson,collectionName,requestId,status,errorCode;
  public int chunkCount,batchCount; public LocalDateTime createdAt,updatedAt;
 }
 @TableName("video_search_state") public static class SearchState {
  @TableId(value="video_id",type=IdType.INPUT) public Long videoId;
  public Long activeIndexId,buildingIndexId; public LocalDateTime updatedAt;
 }
 @TableName("subtitle_chunk") public static class Chunk {
  @TableId(type=IdType.AUTO) public Long id;
  public Long indexId; public int ordinal; public String text,contentHash,pointId;
  public long beginMs,endMs;
 }
 @TableName("subtitle_chunk_segment") public static class ChunkSource {
  @TableId(type=IdType.AUTO) public Long id;
  public Long chunkId,segmentId; public int ordinal,startOffset,endOffset;
 }
 @TableName("chat_turn") public static class ChatTurn {
  @TableId(type=IdType.AUTO) public Long id;
  public Long ownerId,videoId,indexId,transcriptArtifactId;
  public String mode="RAG";
  public String requestId,question,status,answerJson,contextJson,responseJson,errorCode,errorMessage,executionToken;
  public int embeddingRequests,chatRequests;
  public long inputTokens,outputTokens,elapsedMs; public LocalDateTime createdAt,updatedAt,deadlineAt;
 }
}
