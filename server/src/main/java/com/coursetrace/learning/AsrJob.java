package com.coursetrace.learning;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("asr_job")
public class AsrJob {
    @TableId(type=IdType.AUTO) public Long id;
    public Long videoId;
    public Long audioArtifactId;
    public String configHash;
    public int runNo;
    public int submissionNo;
    public String status;
    public String providerTaskId;
    public String temporaryUrl;
    public LocalDateTime temporaryExpiresAt;
    public Long rawArtifactId;
    public int pollCount;
    public String errorCode;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
