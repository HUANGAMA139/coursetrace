package com.coursetrace.learning;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("processing_artifact")
public class Artifact {
    @TableId(type=IdType.AUTO) public Long id;
    public Long videoId;
    public String kind;
    public String fingerprint;
    public String bucketName;
    public String objectKey;
    public long sizeBytes;
    public String sha256;
    public String metadataJson;
    public String responseJson;
    public String state;
    public int requestCount;
    public long inputTokens;
    public long outputTokens;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
