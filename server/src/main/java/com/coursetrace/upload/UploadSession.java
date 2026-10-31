package com.coursetrace.upload;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@TableName("video_upload")
public class UploadSession {
 @TableId(type=IdType.INPUT) public String id;
 public long ownerId,courseId,sizeBytes,chunkSize;
 public String requestId,filename,title,fileSha256,status,executionToken,finalKey,lastError;
 public int chunkCount;
 public Long videoId;
 public LocalDateTime leaseUntil,expiresAt,createdAt,updatedAt,cleanedAt;
}
