package com.coursetrace.agent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.coursetrace.agent.AgentModels.Run;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
@Mapper public interface AgentRunMapper extends BaseMapper<Run> {
 @Select("SELECT * FROM agent_run WHERE turn_id=#{turn}") Run byTurn(long turn);
 @Select("SELECT * FROM agent_run WHERE id=#{id} FOR UPDATE") Run lock(long id);
 @Select("<script>SELECT * FROM agent_run WHERE turn_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
 List<Run> forTurns(@Param("ids")List<Long> ids);
@Update("UPDATE agent_run SET status='UNKNOWN',phase='FINISHED',stop_reason='EXECUTION_EXPIRED',updated_at=#{now} WHERE owner_id=#{owner} AND status='RUNNING' AND deadline_at<=#{now}")
 int expire(@Param("owner")long owner,@Param("now")LocalDateTime now);
}
