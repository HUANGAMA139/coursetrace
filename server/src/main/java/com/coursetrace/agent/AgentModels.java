package com.coursetrace.agent;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
public final class AgentModels {
 private AgentModels(){}
 @TableName("agent_run") public static class Run {
  @TableId(type=IdType.AUTO) public Long id;
  public Long turnId,ownerId,courseId,videoId,indexId,transcriptArtifactId;
  public String configJson,status,phase,stopReason,evidenceJson,executionToken;
  public boolean limited;
  public int rounds,toolCalls,searchCalls,contextCalls,resultBytes;
  public LocalDateTime createdAt,updatedAt,deadlineAt;
 }
 @TableName("agent_step") public static class Step {
  @TableId(type=IdType.AUTO) public Long id;
  public Long runId;
  public int seq,sourceCount;
  public String kind,phase,name,toolCallId,argsJson,resultJson,status,errorCode;
  public boolean cached;
  public long elapsedMs,inputTokens,outputTokens;
  public LocalDateTime createdAt,updatedAt;
 }
}
