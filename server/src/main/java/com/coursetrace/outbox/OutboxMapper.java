package com.coursetrace.outbox;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.time.LocalDateTime;

@Mapper
public interface OutboxMapper extends BaseMapper<OutboxEvent> {
    @Select("SELECT * FROM outbox_event WHERE status='PENDING' AND next_attempt_at<=#{now} ORDER BY created_at,event_id LIMIT 10")
    List<OutboxEvent> due(LocalDateTime now);

    @Update("UPDATE outbox_event SET status='SENT',sent_at=#{now},last_error=NULL WHERE event_id=#{id} AND status='PENDING'")
    int sent(@Param("id") String id, @Param("now") LocalDateTime now);

    @Update("UPDATE outbox_event SET publish_attempts=#{publishAttempts},next_attempt_at=#{nextAttemptAt},last_error=#{lastError},status=#{status} WHERE event_id=#{eventId} AND status='PENDING'")
    int failed(OutboxEvent event);

    @Update("UPDATE outbox_event SET status='CANCELLED' WHERE task_id=#{taskId} AND run_no<>#{runNo} AND status='PENDING'")
    int cancelOld(@Param("taskId") long taskId, @Param("runNo") int runNo);
}
