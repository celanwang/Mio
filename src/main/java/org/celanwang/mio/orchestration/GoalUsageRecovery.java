package org.celanwang.mio.orchestration;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Repairs abandoned usage bookkeeping only. Never creates a run, calls a provider or refunds quota. */
public final class GoalUsageRecovery {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(GoalUsageRecovery.class);
    private final JdbcGoalBudgetStore budgets;
    private final AtomicBoolean started=new AtomicBoolean();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"mio-goal-usage-recovery");thread.setDaemon(true);return thread;});
    private volatile long lastWarning;
    public GoalUsageRecovery(JdbcGoalBudgetStore budgets){this.budgets=budgets;}
    @EventListener(ApplicationReadyEvent.class)
    public void start(){
        if(started.compareAndSet(false,true))timer.scheduleWithFixedDelay(()->{
            try{tick();}catch(Exception failure){
                long now=System.currentTimeMillis();
                if(now-lastWarning>30000){lastWarning=now;LOG.warn("前台用量截止恢复暂不可用，保留预占：{}",failure.getClass().getSimpleName());}
            }
        },0,5,TimeUnit.SECONDS);
    }
    public int tick(){return budgets.recoverExpired(100);}
    @PreDestroy public void stop(){timer.shutdownNow();}
}
