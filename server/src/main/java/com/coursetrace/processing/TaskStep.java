package com.coursetrace.processing;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("task_step")
public class TaskStep {
    @TableId(value = "id", type = IdType.AUTO)
    public Long id;
    public Long taskId;
    public int runNo;
    public String stage;
    public int partId;
    public String status;
    public int attemptCount;
    public int maxAttempts;
    public String executionToken;
    public String resultJson;
    public String errorCode;
    public String errorMessage;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
    public int dispatchSeq;
    public int pollCount;
    public int failureCount;
    public LocalDateTime deadlineAt;
}
