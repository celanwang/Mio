package org.celanwang.mio.memory;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@link MemoryTaskExecutor} 的本地实现：单线程守护线程池，任务串行执行。
 */
@Component
public class LocalMemoryTaskExecutor implements MemoryTaskExecutor {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "memory-tasks");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void execute(Runnable task) {
        executor.execute(task);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
