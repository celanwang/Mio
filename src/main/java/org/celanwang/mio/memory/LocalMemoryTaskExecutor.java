package org.celanwang.mio.memory;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.celanwang.mio.memory.jobs.*;
import org.springframework.beans.factory.annotation.Value;

/**
 * {@link MemoryTaskExecutor} 的本地实现：单线程守护线程池，任务串行执行。
 */
@Component
public class LocalMemoryTaskExecutor implements MemoryTaskExecutor {

    private final ThreadPoolExecutor executor;
    private final MemoryCallBudget budget;private final AtomicLong rejected=new AtomicLong();private final boolean enabled;
    public LocalMemoryTaskExecutor(MemoryCallBudget budget,@Value("${app.memory.jobs.transient-queue-capacity:32}")int capacity,@Value("${app.memory.jobs.enabled:true}")boolean enabled){
        if(capacity<1||capacity>1000)throw new IllegalArgumentException("记忆临时任务容量无效");this.budget=budget;this.enabled=enabled;
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(capacity),r->{var thread=new Thread(r,"memory-derived-cache");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void execute(Runnable task) {
        if(!enabled){rejected.incrementAndGet();return;}
        try{executor.execute(()->{try(var context=MemoryWorkContext.open(budget,null)){task.run();}catch(Exception ignored){/* Rebuildable caches and habit scans retry from their next foreground observation. */}});}
        catch(RejectedExecutionException full){rejected.incrementAndGet();}
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
    public java.util.Map<String,Object> diagnostics(){return java.util.Map.of("active",executor.getActiveCount(),"queued",executor.getQueue().size(),"rejected",rejected.get());}
}
