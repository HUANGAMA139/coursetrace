package com.coursetrace.agent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.coursetrace.agent.AgentModels.Step;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
@Mapper public interface AgentStepMapper extends BaseMapper<Step> {
 @Select("SELECT * FROM agent_step WHERE run_id=#{run} ORDER BY seq") List<Step> steps(long run);
 @Update("UPDATE agent_step SET status='UNKNOWN',error_code='EXECUTION_EXPIRED',updated_at=#{now} WHERE status='STARTED' AND run_id IN (SELECT id FROM agent_run WHERE owner_id=#{owner} AND status='UNKNOWN')")
 int expire(@Param("owner")long owner,@Param("now")LocalDateTime now);
}
