package org.celanwang.mio.goals;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GoalProgramDispatcher {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(GoalProgramDispatcher.class);
    private final JdbcGoalWakeups wakeups;private final GoalProgramExecutor executor;private final Semaphore slots=new Semaphore(2);private final AtomicBoolean started=new AtomicBoolean();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"mio-goal-wakeups");t.setDaemon(true);return t;});
    private final ExecutorService workers=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"mio-goal-preparation");t.setDaemon(true);return t;});private long lastWarning;
    public GoalProgramDispatcher(JdbcGoalWakeups wakeups,GoalProgramExecutor executor){this.wakeups=wakeups;this.executor=executor;}
    @EventListener(ApplicationReadyEvent.class) public void start(){if(started.compareAndSet(false,true))timer.scheduleWithFixedDelay(()->{try{tick();}catch(Exception error){if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();LOG.warn("持续目标调度暂不可用：{}",error.getClass().getSimpleName());}}},0,1000,TimeUnit.MILLISECONDS);}
    private GoalTriggers triggers;public GoalProgramDispatcher withTriggers(GoalTriggers triggers){this.triggers=triggers;return this;}
    public void tick(){if(triggers!=null)triggers.poll();wakeups.scheduleDue();while(slots.tryAcquire()){try{var claim=wakeups.claim();if(claim.isEmpty()){slots.release();return;}workers.execute(()->{try{executor.run(claim.get());}catch(Exception error){LOG.warn("目标推进未完成，保留执行状态供恢复：{}",error.getClass().getSimpleName());}finally{slots.release();}});}catch(RuntimeException error){slots.release();throw error;}}}
    @PreDestroy public void stop(){timer.shutdownNow();workers.shutdownNow();}
}
