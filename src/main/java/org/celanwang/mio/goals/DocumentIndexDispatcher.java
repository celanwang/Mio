package org.celanwang.mio.goals;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One indexing worker prevents document batches from flooding foreground resources. */
public final class DocumentIndexDispatcher {
    private final DocumentIndexJobs jobs;
    private final AtomicBoolean started=new AtomicBoolean();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"mio-document-index");t.setDaemon(true);return t;});
    public DocumentIndexDispatcher(DocumentIndexJobs jobs){this.jobs=jobs;}
    @EventListener(ApplicationReadyEvent.class) public void start(){if(started.compareAndSet(false,true))worker.scheduleWithFixedDelay(this::tick,0,2,TimeUnit.SECONDS);}
    public void tick(){try{jobs.claim().ifPresent(jobs::run);}catch(RuntimeException unavailable){/* Durable jobs remain recoverable on the next tick. */}}
    @PreDestroy public void stop(){worker.shutdownNow();}
}
