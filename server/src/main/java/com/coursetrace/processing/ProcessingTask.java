package com.coursetrace.processing;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("processing_task")
public class ProcessingTask {
    @TableId(value = "id", type = IdType.AUTO)
    public Long id;
    public Long videoId;
    public int pipelineVersion;
    public int runNo;
    public String currentStage;
    public String status;
    public String errorCode;
    public String errorMessage;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
    public String parametersJson;
    public boolean confirmResubmitUncertain;
}
