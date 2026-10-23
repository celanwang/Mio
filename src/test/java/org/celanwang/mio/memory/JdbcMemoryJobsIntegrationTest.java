package org.celanwang.mio.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.*;
import org.celanwang.mio.memory.jobs.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Flux;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfEnvironmentVariable(named="MIO_TEST_PG_URL",matches="jdbc:postgresql:.*")
class JdbcMemoryJobsIntegrationTest {
    MemoryJobsDatabase db;final ObjectMapper json=new ObjectMapper();final List<MemoryJobDispatcher> workers=new ArrayList<>();
    final AtomicInteger modelCalls=new AtomicInteger();final AtomicReference<Runnable> duringModel=new AtomicReference<>(()->{});
    final String output="{\"pages\":[{\"slug\":\"taste\",\"title\":\"口味\",\"content\":\"不要辣椒\"}],\"triples\":[{\"subject\":\"user\",\"predicate\":\"prefers\",\"object\":\"mild\"}]}";
    @BeforeEach void initialize(){db=new MemoryJobsDatabase();}
    @AfterEach void cleanup(){workers.forEach(MemoryJobDispatcher::stop);db.close();}
    Model model(String answer){var model=mock(Model.class);when(model.stream(anyList(),any(),any())).thenAnswer(inv->Flux.defer(()->{modelCalls.incrementAndGet();duringModel.get().run();return Flux.just(ChatResponse.builder().content(List.of(TextBlock.builder().text(answer).build())).usage(ChatUsage.builder().inputTokens(7).outputTokens(3).build()).build());}));return model;}
    ProfileDistiller distiller(Model model){return new ProfileDistiller(model,db.profiles,db.entities,json,true);}
    MemoryJobDispatcher worker(JdbcMemoryUsage usage,ProfileDistiller distiller,EmbeddingClient embedding,boolean index){var worker=new MemoryJobDispatcher(db.jobs,usage,db.episodes,db.vectors,embedding,distiller,json,true,index,true,2,100);workers.add(worker);return worker;}
    MemoryJobDispatcher worker(Model model){return worker(db.usage,distiller(model),mock(EmbeddingClient.class),false);}

    @Test void originalAndEveryDerivedJobCommitTogetherAndDuplicatesDoNotReenqueue(){
        var first=db.append("one",true,true);assertThat(db.jobs.find("INDEX:one")).isPresent();assertThat(db.jobs.find("DISTILL:one")).isPresent();
        assertThat(db.episodes.append(first)).isEqualTo(EpisodeStore.AppendResult.EXISTING);assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM memory_jobs",Integer.class)).isEqualTo(2);
        db.jdbc.execute("ALTER TABLE memory_jobs ADD CONSTRAINT reject_distill CHECK(kind <> 'DISTILL') NOT VALID");
        assertThat(db.episodes.append(new EpisodeStore.Episode("failed","s",1,"user","sensitive original"))).isEqualTo(EpisodeStore.AppendResult.FAILED);
        assertThat(db.episodes.find("failed")).isEmpty();assertThat(db.jobs.find("INDEX:failed")).isEmpty();
    }
    @Test void competingInstancesCannotPublishOverAnExpiredGeneration() throws Exception {
        db.append("one",true,false);var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{
            var futures=List.of(1,2).stream().map(i->pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);return new JdbcMemoryJobs(db.jdbc,db.source,3,120000,1).claim(Set.of(MemoryJob.Kind.INDEX));})).toList();
            var claims=new ArrayList<MemoryJob.Claim>();for(var future:futures)future.get(10,TimeUnit.SECONDS).ifPresent(claims::add);assertThat(claims).hasSize(1);
            var old=claims.get(0);db.expire(old.id());var next=db.jobs.claim(Set.of(MemoryJob.Kind.INDEX)).orElseThrow();
            assertThat(next.generation()).isGreaterThan(old.generation());assertThatThrownBy(()->db.jobs.checkpoint(old,"{}" )).isInstanceOf(MemoryJob.LostClaim.class);
            assertThatThrownBy(()->db.jobs.complete(old,()->{throw new AssertionError("obsolete apply");})).isInstanceOf(MemoryJob.LostClaim.class);
            db.jobs.complete(next,()->{});assertThat(db.jobs.find(next.id()).orElseThrow().state()).isEqualTo(MemoryJob.State.DONE);
        }finally{pool.shutdownNow();}
    }
    @Test void persistedModelResultSurvivesRestartAndApplyFailureWithoutAnotherModelCall() throws Exception {
        var episode=db.append("one",false,true);var claim=db.jobs.claim(Set.of(MemoryJob.Kind.DISTILL)).orElseThrow();var distiller=distiller(model(output));ProfileDistiller.Plan plan;
        try(var context=MemoryWorkContext.open(db.usage,claim)){plan=distiller.prepare(episode,claim);}
        db.jobs.checkpoint(claim,json.writeValueAsString(plan));
        assertThatThrownBy(()->db.jobs.complete(claim,()->{distiller.apply(plan,claim);throw new IllegalStateException("crash before acknowledgement");})).isInstanceOf(IllegalStateException.class);
        assertThat(db.profiles.pagesStrict()).isEmpty();assertThat(db.entities.all()).isEmpty();db.expire(claim.id());
        var never=mock(Model.class);assertThat(worker(never).runOne()).isTrue();verifyNoInteractions(never);
        assertThat(modelCalls.get()).isEqualTo(1);assertThat(db.profiles.pagesStrict()).extracting(ProfilePage::content).containsExactly("不要辣椒");assertThat(db.entities.all()).hasSize(1);
        assertThat(db.jobs.find(claim.id()).orElseThrow().state()).isEqualTo(MemoryJob.State.DONE);assertThat(worker(never).runOne()).isFalse();
        assertThat(db.jdbc.queryForObject("SELECT calls FROM memory_usage_days",Integer.class)).isEqualTo(1);
    }
    @Test void savedResultCanRecoverEvenAfterTheLastNetworkAttempt() throws Exception {
        var episode=db.append("one",false,true);var claim=db.jobs.claim(Set.of(MemoryJob.Kind.DISTILL)).orElseThrow();
        db.jdbc.update("UPDATE memory_jobs SET attempts=3 WHERE id=?",claim.id());var plan=new ProfileDistiller.Plan(1,episode.id(),claim.sequence(),episode.timestamp(),Map.of(),new ProfileDistiller.DistillResult(List.of(),List.of()));
        db.jobs.checkpoint(claim,json.writeValueAsString(plan));db.expire(claim.id());var model=mock(Model.class);
        assertThat(worker(model).runOne()).isTrue();assertThat(db.jobs.find(claim.id()).orElseThrow().state()).isEqualTo(MemoryJob.State.DONE);verifyNoInteractions(model);
    }
    @Test void manualEditsAndDeletionDuringModelCallRemainProtected(){
        for(boolean delete:List.of(false,true)){
            String id=delete?"deleted":"edited";db.append(id,false,true);db.profiles.saveInferred(new ProfilePage("taste","口味","global","inferred",1,1,"旧内容"),null);
            duringModel.set(()->{if(delete)db.profiles.deletePage("taste");else db.profiles.savePage(new ProfilePage("taste","口味","global","explicit",1,2,"人工内容"));});
            assertThat(worker(model(output)).runOne()).isTrue();
            if(delete)assertThat(db.profiles.pagesStrict()).isEmpty();else assertThat(db.profiles.pagesStrict()).extracting(ProfilePage::content).containsExactly("人工内容");
            assertThat(db.jobs.find("DISTILL:"+id).orElseThrow().errorCode()).isEqualTo("PRESERVED_NEWER_OR_MANUAL");
        }
    }
    @Test void olderSourceCannotOverwriteANewerInferredPageAndDistillationIsSerialized(){
        db.append("one",false,true);db.append("two",false,true);var first=db.jobs.claim(Set.of(MemoryJob.Kind.DISTILL)).orElseThrow();
        assertThat(db.jobs.claim(Set.of(MemoryJob.Kind.DISTILL))).isEmpty();db.jobs.complete(first,()->{});var second=db.jobs.claim(Set.of(MemoryJob.Kind.DISTILL)).orElseThrow();
        assertThat(db.profiles.saveInferred(new ProfilePage("taste","口味","global","inferred",1,1,"新证据"),null,second.sequence())).isTrue();
        long revision=db.profiles.snapshot().revisions().get("taste");
        assertThat(db.profiles.saveInferred(new ProfilePage("taste","口味","global","inferred",1,1,"旧证据"),revision,first.sequence())).isFalse();
        assertThat(db.profiles.pagesStrict().get(0).content()).isEqualTo("新证据");
    }
    @Test void unshownOversizedInferredPagesAreNeverRewrittenFromTruncatedContext(){
        String original="原文".repeat(12000);db.profiles.saveInferred(new ProfilePage("taste","口味","global","inferred",1,1,original),null);
        db.append("one",false,true);assertThat(worker(model(output)).runOne()).isTrue();
        assertThat(db.profiles.pagesStrict().get(0).content()).isEqualTo(original);
        assertThat(db.jobs.find("DISTILL:one").orElseThrow().errorCode()).isEqualTo("PRESERVED_NEWER_OR_MANUAL");
    }
    @Test void malformedResultsRetryOnlyWithinTheConfiguredLimit(){
        db.append("one",false,true);var worker=worker(model("not JSON"));
        for(int i=0;i<3;i++){db.due("DISTILL:one");assertThat(worker.runOne()).isTrue();}
        assertThat(worker.runOne()).isFalse();var job=db.jobs.find("DISTILL:one").orElseThrow();assertThat(job.state()).isEqualTo(MemoryJob.State.DEAD);assertThat(job.attempts()).isEqualTo(3);assertThat(modelCalls.get()).isEqualTo(3);
        assertThat(db.episodes.find("one")).isPresent();assertThat(db.profiles.pagesStrict()).isEmpty();
    }
    @Test void exhaustedBudgetDefersWithoutSpendingAttemptsOrCallingTheModel(){
        var usage=new JdbcMemoryUsage(db.jdbc,db.source,new JdbcMemoryUsage.Limits(1,100000,100000));usage.begin(MemoryCallBudget.Kind.MODEL,1,null).settle(1L,true);
        db.append("one",false,true);var model=mock(Model.class);assertThat(worker(usage,distiller(model),mock(EmbeddingClient.class),false).runOne()).isTrue();
        var job=db.jobs.find("DISTILL:one").orElseThrow();assertThat(job.state()).isEqualTo(MemoryJob.State.RETRY);assertThat(job.attempts()).isZero();assertThat(job.errorCode()).isEqualTo("DAILY_BUDGET");assertThat(job.nextAttemptAt()).isGreaterThan(db.jobs.now());verifyNoInteractions(model);
    }
    @Test void vectorResultCheckpointIsAppliedAfterRestartWithoutCallingEmbeddingAgain() throws Exception {
        db.append("one",true,false);var claim=db.jobs.claim(Set.of(MemoryJob.Kind.INDEX)).orElseThrow();float[] vector=new float[1024];vector[0]=1;
        db.jobs.checkpoint(claim,json.writeValueAsString(new MemoryJobDispatcher.IndexPlan(1,"one","fixture-embedding",vector)));db.expire(claim.id());
        var embedding=mock(EmbeddingClient.class);when(embedding.available()).thenReturn(true);when(embedding.modelName()).thenReturn("fixture-embedding");
        assertThat(worker(db.usage,distiller(mock(Model.class)),embedding,true).runOne()).isTrue();verify(embedding,never()).embed(anyList());
        assertThat(db.vectors.containsStrict("one")).isTrue();assertThat(db.jobs.find(claim.id()).orElseThrow().state()).isEqualTo(MemoryJob.State.DONE);
    }
    @Test void actualDispatcherBoundsConcurrentProviderCallsAndLeavesOverflowDurable() throws Exception {
        for(int i=0;i<3;i++)db.append("parallel-"+i,true,false);
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var calls=new AtomicInteger();var embedding=mock(EmbeddingClient.class);
        when(embedding.available()).thenReturn(true);when(embedding.modelName()).thenReturn("fixture");
        when(embedding.embed(anyList())).thenAnswer(inv->{calls.incrementAndGet();var charge=MemoryWorkContext.begin(MemoryCallBudget.Kind.EMBEDDING,1);entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture timed out");charge.settle(1L,true);float[] vector=new float[1024];vector[0]=1;return Optional.of(List.of(vector));});
        var worker=worker(db.usage,distiller(mock(Model.class)),embedding,true);
        try{
            worker.tick();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();worker.tick();assertThat(calls.get()).isEqualTo(2);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM memory_jobs WHERE state='READY'",Integer.class)).isEqualTo(1);
            release.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline&&db.jdbc.queryForObject("SELECT COUNT(*) FROM memory_jobs WHERE state='DONE'",Integer.class)<2)Thread.sleep(10);
            assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM memory_jobs WHERE state='DONE'",Integer.class)).isEqualTo(2);
        }finally{release.countDown();}
    }
    @Test void largeMixedHistoryRecallsTailImmediatelyAndBackfillsBeyondTwoThousand(){
        var batch=new ArrayList<Object[]>();for(int i=0;i<2105;i++)batch.add(new Object[]{"legacy-"+String.format("%04d",i),"s",(long)i,"user","历史消息 "+i});
        db.jdbc.batchUpdate("INSERT INTO messages(id,session_id,ts,role,text) VALUES (?,?,?,?,?)",batch);
        float[] tail=new float[1024];tail[0]=1;for(int i=2000;i<2105;i++)db.vectors.upsertStrict("legacy-"+String.format("%04d",i),tail);
        var retriever=new EpisodeRetriever(true,3,.6,db.episodes,db.vectors,mock(EmbeddingClient.class));
        assertThat(retriever.recall("历史",()->tail)).contains("历史消息 20");
        int scanned=0;
        for(int i=0;i<30;i++){
            int page=db.jobs.backfillPage(100);scanned+=page;
            // Model completed index work to let the bounded backfill window advance; actual vector publication is tested above.
            db.jdbc.update("UPDATE memory_jobs SET state='DONE' WHERE kind='INDEX'");
            if(page==0)break;
        }
        assertThat(scanned).isEqualTo(2105);assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM memory_jobs WHERE kind='INDEX'",Integer.class)).isEqualTo(2000);
        assertThat(db.jdbc.queryForObject("SELECT finished FROM memory_backfills",Integer.class)).isEqualTo(1);assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM memory_jobs WHERE kind='DISTILL'",Integer.class)).isZero();
    }
}
