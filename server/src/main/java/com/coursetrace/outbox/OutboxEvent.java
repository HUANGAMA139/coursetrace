package com.coursetrace.outbox;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("outbox_event")
public class OutboxEvent {
    @TableId(value = "event_id", type = IdType.INPUT)
    public String eventId;
    public Long taskId;
    public int runNo;
    public int pipelineVersion;
    public String stage;
    public int partId;
    public String topic;
    public String messageKey;
    public String payloadJson;
    public String status;
    public int publishAttempts;
    public LocalDateTime nextAttemptAt;
    public String lastError;
    public LocalDateTime createdAt;
    public LocalDateTime sentAt;
    public int dispatchSeq;
}
