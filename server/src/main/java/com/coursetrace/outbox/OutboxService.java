package com.coursetrace.outbox;

import com.coursetrace.processing.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;

@Service
public class OutboxService {
    private final OutboxMapper outbox;
    private final ProcessingMapper tasks;
    private final TaskStepMapper steps;
    private final ProcessingProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    public OutboxService(OutboxMapper outbox,ProcessingMapper tasks,TaskStepMapper steps,
                         ProcessingProperties properties,Clock clock,PlatformTransactionManager manager) {
        this.outbox=outbox; this.tasks=tasks; this.steps=steps; this.properties=properties;
        this.clock=clock; this.tx=new TransactionTemplate(manager);
    }

    public void publishFailed(OutboxEvent attempted) {
        tx.executeWithoutResult(transaction -> {
            var task=tasks.lock(attempted.taskId);
            var event=outbox.selectById(attempted.eventId);
            if (event==null || !"PENDING".equals(event.status)) return;
            event.publishAttempts++;
            event.lastError="消息服务暂时不可用";
            event.nextAttemptAt=now().plusSeconds(Math.min(60L,1L<<Math.min(event.publishAttempts,6)));
            boolean exhausted=event.publishAttempts>=properties.getMaxPublishAttempts();
            event.status=task==null || task.runNo!=event.runNo?"CANCELLED":exhausted?"DEAD":"PENDING";
            outbox.failed(event);
            if (!"DEAD".equals(event.status)) return;
            var current=steps.current(task.id,task.runNo);
            var match=current.stream().filter(s->s.stage.equals(event.stage)&&s.partId==event.partId&&s.dispatchSeq==event.dispatchSeq).findFirst();
            if(match.isEmpty()||!java.util.List.of("PENDING","WAITING_EXTERNAL","RETRY_WAIT").contains(match.get().status))return;
            var step=match.get();
            step.errorCode="TASK_DELIVERY_FAILED"; step.errorMessage="任务暂时无法投递，消息服务恢复后可手动重试";
            step.updatedAt=now(); step.finishedAt=step.updatedAt;
            if(task.pipelineVersion==1){if(steps.failWaiting(step)!=1)return;}else{step.status="FAILED";steps.saveLearning(step);}
            task.status="FAILED"; task.errorCode=step.errorCode; task.errorMessage=step.errorMessage;
            task.updatedAt=step.updatedAt; task.finishedAt=step.finishedAt; tasks.save(task);
        });
    }

    private LocalDateTime now() { return LocalDateTime.now(clock); }
}
