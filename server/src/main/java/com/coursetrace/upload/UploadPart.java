package com.coursetrace.upload;
import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
@TableName("video_upload_part")
public class UploadPart {
 @TableId(type=IdType.AUTO) public Long id;
 public String uploadId,objectKey,sha256;
 public int partNo;
 public long sizeBytes;
 public LocalDateTime createdAt;
}
