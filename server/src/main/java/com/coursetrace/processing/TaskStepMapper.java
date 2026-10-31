package com.coursetrace.processing;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.time.LocalDateTime;

@Mapper
public interface TaskStepMapper extends BaseMapper<TaskStep> {
    @Select("SELECT * FROM task_step WHERE task_id=#{taskId} AND run_no=#{runNo} ORDER BY id")
    List<TaskStep> current(@Param("taskId") long taskId, @Param("runNo") int runNo);

    @Select("<script>SELECT s.* FROM task_step s JOIN processing_task t ON t.id=s.task_id AND t.run_no=s.run_no WHERE s.task_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY s.id</script>")
    List<TaskStep> batch(@Param("ids") List<Long> ids);

    @Update("UPDATE task_step SET status='RUNNING',attempt_count=attempt_count+1,execution_token=#{token},error_code=NULL,error_message=NULL,started_at=#{now},updated_at=#{now},finished_at=NULL WHERE id=#{id} AND status IN ('PENDING','RETRY_WAIT') AND attempt_count < max_attempts")
    int claim(@Param("id") long id, @Param("token") String token, @Param("now") LocalDateTime now);

    @Update("UPDATE task_step SET status=#{status},result_json=#{resultJson},error_code=#{errorCode},error_message=#{errorMessage},updated_at=#{updatedAt},finished_at=#{finishedAt} WHERE id=#{id} AND run_no=#{runNo} AND execution_token=#{executionToken} AND status='RUNNING'")
    int finish(TaskStep step);

    @Update("UPDATE task_step SET status='FAILED',error_code=#{errorCode},error_message=#{errorMessage},updated_at=#{updatedAt},finished_at=#{finishedAt} WHERE id=#{id} AND status IN ('PENDING','RETRY_WAIT')")
    int failWaiting(TaskStep step);
    @Update("UPDATE task_step SET status=#{status},attempt_count=#{attemptCount},execution_token=#{executionToken},dispatch_seq=#{dispatchSeq},poll_count=#{pollCount},failure_count=#{failureCount},deadline_at=#{deadlineAt},result_json=#{resultJson},error_code=#{errorCode},error_message=#{errorMessage},updated_at=#{updatedAt},started_at=#{startedAt},finished_at=#{finishedAt} WHERE id=#{id} AND run_no=#{runNo}")
    int saveLearning(TaskStep step);
    @Select("SELECT * FROM task_step WHERE task_id=#{taskId} AND stage=#{stage} AND status='SUCCEEDED' ORDER BY run_no DESC,id DESC LIMIT 1")
    TaskStep latestSucceeded(@Param("taskId") long taskId,@Param("stage") String stage);
}
