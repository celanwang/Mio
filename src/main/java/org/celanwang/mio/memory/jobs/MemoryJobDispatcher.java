package org.celanwang.mio.memory.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.memory.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded memory-only consumers. No business tools, approvals or main agent execution live here. */
public final class MemoryJobDispatcher {
    private static final Logger LOG=LoggerFactory.getLogger(MemoryJobDispatcher.class);
    private final JdbcMemoryJobs jobs;private final JdbcMemoryUsage usage;private final EpisodeStore episodes;private final VectorStore vectors;
    private final EmbeddingClient embedding;private final ProfileDistiller distiller;private final ObjectMapper json;
    private final boolean enabled,indexEnabled,modelConfigured;private final int parallelism,pageSize;private final Semaphore slots;
    private final ThreadPoolExecutor workers;private final ScheduledExecutorService polling;private final AtomicBoolean started=new AtomicBoolean();private volatile long lastWarning;
    public MemoryJobDispatcher(JdbcMemoryJobs jobs,JdbcMemoryUsage usage,EpisodeStore episodes,VectorStore vectors,EmbeddingClient embedding,ProfileDistiller distiller,ObjectMapper json,
                               boolean enabled,boolean indexEnabled,boolean modelConfigured,int parallelism,int pageSize){
        if(parallelism<1||parallelism>4||pageSize<1||pageSize>500)throw new IllegalArgumentException("记忆消费者并发或分页参数无效");
        this.jobs=jobs;jobs.configureIndex(vectors.namespace());this.usage=usage;this.episodes=episodes;this.vectors=vectors;this.embedding=embedding;this.distiller=distiller;
        this.json=json.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.enabled=enabled;this.indexEnabled=indexEnabled;this.modelConfigured=modelConfigured;this.parallelism=parallelism;this.pageSize=pageSize;slots=new Semaphore(parallelism);
        workers=new ThreadPoolExecutor(parallelism,parallelism,0,TimeUnit.MILLISECONDS,new SynchronousQueue<>(),r->{var thread=new Thread(r,"memory-durable-worker");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
        polling=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"memory-durable-poll");thread.setDaemon(true);return thread;});
    }
    @EventListener(ApplicationReadyEvent.class) public void start(){
        if(enabled&&started.compareAndSet(false,true))polling.scheduleWithFixedDelay(()->{try{tick();}catch(Exception failure){if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();LOG.warn("记忆后台调度暂不可用：{}",failure.getClass().getSimpleName());}}},0,1000,TimeUnit.MILLISECONDS);
    }
    private Set<MemoryJob.Kind> enabledKinds(){
        var kinds=EnumSet.noneOf(MemoryJob.Kind.class);if(indexEnabled&&embedding.available())kinds.add(MemoryJob.Kind.INDEX);if(distiller.enabled()&&modelConfigured)kinds.add(MemoryJob.Kind.DISTILL);return kinds;
    }
    public void tick(){
        if(!enabled)return;
        try{usage.recoverExpired();}catch(Exception unavailable){warn(unavailable);}
        if(indexEnabled)try{jobs.backfillPage(pageSize);}catch(Exception unavailable){warn(unavailable);}
        for(int i=0;i<parallelism&&slots.tryAcquire();i++){
            try{var claim=jobs.claim(enabledKinds());if(claim.isEmpty()){slots.release();break;}workers.execute(()->{try{process(claim.get());}finally{slots.release();}});}
            catch(RuntimeException failure){slots.release();throw failure;}
        }
    }
    /** Deterministic single-step entry used by offline tests; no polling or provider fallback. */
    public boolean runOne(){if(!enabled)return false;var claim=jobs.claim(enabledKinds());if(claim.isEmpty())return false;process(claim.get());return true;}
    private void process(MemoryJob.Claim claim){
        try(var context=MemoryWorkContext.open(usage,claim)){
            var episode=episodes.find(claim.messageId()).orElseThrow(()->new IllegalArgumentException("原文缺失"));
            if(claim.kind()==MemoryJob.Kind.INDEX){
                if(vectors.containsStrict(episode.id())){jobs.complete(claim,()->{});return;}
                IndexPlan plan;
                if(claim.result()==null){var embedded=embedding.embed(List.of(episode.text())).orElseThrow(()->new IllegalStateException("向量服务未返回结果"));plan=new IndexPlan(1,episode.id(),embedding.modelName(),embedded.get(0));jobs.checkpoint(claim,json.writeValueAsString(plan));}
                else plan=json.readValue(claim.result(),IndexPlan.class);
                if(!plan.messageId().equals(claim.messageId())||!plan.model().equals(embedding.modelName()))throw new IllegalArgumentException("向量检查点版本或来源已改变");
                jobs.complete(claim,()->vectors.upsertStrict(episode.id(),plan.vector()));
            }else{
                ProfileDistiller.Plan plan;
                if(claim.result()==null){plan=distiller.prepare(episode,claim);jobs.checkpoint(claim,json.writeValueAsString(plan));}
                else plan=json.readValue(claim.result(),ProfileDistiller.Plan.class);
                jobs.completeWithOutcome(claim,()->distiller.apply(plan,claim)>0?"PRESERVED_NEWER_OR_MANUAL":"");
            }
        }catch(MemoryCallBudget.Exhausted exhausted){try{jobs.defer(claim,usage.nextReset(),"DAILY_BUDGET");}catch(MemoryJob.LostClaim ignored){}catch(Exception storage){LOG.warn("记忆配额延后状态未提交，保留租约恢复");}}
        catch(MemoryJob.LostClaim lost){/* Late results cannot acknowledge or publish over a newer generation. */}
        catch(Exception failure){try{jobs.failed(claim,failure instanceof MemoryCallBudget.InputTooLarge?"INPUT_TOO_LARGE":failure instanceof IllegalArgumentException?"INVALID_RESULT":failure instanceof org.springframework.dao.DataAccessException?"STORAGE_UNAVAILABLE":"PROCESSING_FAILED");}catch(Exception storage){LOG.warn("记忆失败状态未提交，保留租约恢复");}}
    }
    public record IndexPlan(int schemaVersion,String messageId,String model,float[] vector){
        public IndexPlan {if(schemaVersion!=1||messageId==null||model==null||vector==null||vector.length!=1024)throw new IllegalArgumentException("向量检查点无效");vector=vector.clone();for(float value:vector)if(!Float.isFinite(value))throw new IllegalArgumentException("向量值无效");}
        @Override public float[] vector(){return vector.clone();}
    }
    public Map<String,Object> diagnostics(){return Map.of("enabled",enabled,"started",started.get(),"active",parallelism-slots.availablePermits(),"parallelism",parallelism,"availableKinds",enabledKinds());}
    private void warn(Exception failure){if(System.currentTimeMillis()-lastWarning>30000){lastWarning=System.currentTimeMillis();LOG.warn("记忆后台维护暂不可用：{}",failure.getClass().getSimpleName());}}
    @PreDestroy public void stop(){started.set(false);polling.shutdownNow();workers.shutdownNow();}
}
