package org.celanwang.mio.goals;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.ops.SchemaMigrations;
import org.celanwang.mio.orchestration.*;
import org.celanwang.mio.memory.*;
import org.celanwang.mio.memory.jobs.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="MIO_TEST_PG_URL",matches="jdbc:postgresql:.*")
class JdbcPlatformIntegrationTest {
    JdbcTemplate admin,jdbc;DriverManagerDataSource source;String schema;final ObjectMapper json=new ObjectMapper();JdbcCoordinationStore store;
    @BeforeEach void init(){String url=System.getenv("MIO_TEST_PG_URL"),user=System.getenv().getOrDefault("MIO_TEST_PG_USERNAME","postgres"),password=System.getenv().getOrDefault("MIO_TEST_PG_PASSWORD","");admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));schema="mio_platform_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);jdbc=new JdbcTemplate(source);new SchemaMigrations(jdbc,source).migrate();store=new JdbcCoordinationStore(jdbc,source,json);store.createGoal(new GoalRecord("g","s",1,"original","original",1,1));}
    @AfterEach void close(){if(admin!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    @Test void unifiedMigrationsAreRepeatableAndContextRenewalRetainsUsageAndOriginals(){
        new SchemaMigrations(jdbc,source).migrate();assertThat(jdbc.queryForObject("SELECT count(*) FROM schema_migrations",Long.class)).isEqualTo(4);
        var budgets=new JdbcGoalBudgetStore(jdbc,source,json);budgets.open("g",new GoalBudgetStore.Limits(20,20,20,100));String call=budgets.reserve("g",GoalBudgetStore.Kind.MODEL);budgets.settle("g",call,3L,true);var before=budgets.find("g").orElseThrow();
        var registry=new AgentRegistry(List.of(new AgentCapability("domain","domain","domain","s","v1",List.of())));var programs=new JdbcGoalPrograms(jdbc,source,json,store,registry);
        programs.save("s",new GoalProgram.Request(0,1,Map.of(),List.of(),new GoalProgram.Policy(false,System.currentTimeMillis()+100000,5,60,1,Set.of("domain"))));var continuity=new GoalContinuity(jdbc,source,store,programs,new JdbcExecutionLeaseStore(jdbc,source),json);
        var renewed=continuity.renew("s",new GoalContinuity.Renew(1,System.currentTimeMillis()+200000,10,"continue"));assertThat(renewed.policy().maxActivations()).isEqualTo(15);assertThat(budgets.find("g").orElseThrow()).isEqualTo(before);
        continuity.renew("s",new GoalContinuity.Renew(2,System.currentTimeMillis()+200000,0,"explicit additional budget",2,0,5,0));var extended=budgets.find("g").orElseThrow();assertThat(extended.models()).isEqualTo(before.models());assertThat(extended.knownTokens()).isEqualTo(3);assertThat(extended.limits().models()).isEqualTo(before.limits().models()+2);before=extended;
        continuity.checkpoint("s",new GoalContinuity.Context(1,"new confirmed intent","new phase"));assertThat(store.goal("s").orElseThrow().trustedIntent()).isEqualTo("new confirmed intent");assertThat(continuity.archives("s").get(0).get("content").toString()).contains("original");assertThat(budgets.find("g").orElseThrow()).isEqualTo(before);
        for(int i=0;i<20;i++)store.createTask(new TaskRecord("done"+i,"s","g",2,new TaskSpec("done"+i,"domain","done",null,null,TaskScope.PREPARE),"child"+i,"v1",TaskStatus.COMPLETED,"done",null,List.of(),false,List.of(),0,1,1));
        var runtime=new TaskRuntime(store,registry,(t,g,input,execution)->reactor.core.publisher.Mono.just(new TaskWorker.Result("fresh",false,List.of(),null)),new org.celanwang.mio.security.PolicyEngine("shadow"),java.time.Clock.systemUTC(),1);var run=new RunBudget(new RunBudget.Limits(5,5,5,1000,10000,10000),java.time.Clock.systemUTC());assertThat(runtime.submit("s",new TaskSpec("fresh","domain","fresh",null,null,TaskScope.PREPARE),run).block().status()).isEqualTo(TaskStatus.COMPLETED);
    }
    @Test void vectorNamespacesAndSourceHashesPreventMixedOrStaleRecall(){
        jdbc.update("INSERT INTO messages VALUES ('m','s',1,'user','original')");var a=mock(EmbeddingClient.class);var b=mock(EmbeddingClient.class);when(a.namespace()).thenReturn("model-a");when(b.namespace()).thenReturn("model-b");var vectorsA=new PgVectorStore(jdbc,a);var vectorsB=new PgVectorStore(jdbc,b);float[] vector=new float[1024];vector[0]=1;vectorsA.upsertStrict("m",vector);
        assertThat(vectorsA.search(vector,3)).hasSize(1);assertThat(vectorsB.search(vector,3)).isEmpty();jdbc.update("UPDATE messages SET text='changed' WHERE id='m'");assertThat(vectorsA.containsStrict("m")).isFalse();assertThat(vectorsA.search(vector,3)).isEmpty();
        var jobs=new JdbcMemoryJobs(jdbc,source,3,120000,1);jobs.configureIndex("model-b");jobs.backfillPage(10);assertThat(jdbc.queryForObject("SELECT id FROM memory_jobs WHERE kind='INDEX'",String.class)).isEqualTo("INDEX:model-b:m");
    }
    @Test void documentCitationsTrackRevisionsAndDeletionRemovesDerivedData(){
        var docs=new JdbcDocuments(jdbc,source,store);var doc=docs.save("d",new JdbcDocuments.Save("long","prefix ".repeat(200)+"needle",0),null);var results=docs.search("needle");assertThat(results).isNotEmpty();assertThat(results.get(0)).containsKey("start_offset").containsEntry("revision",1L);assertThat(docs.search("")).hasSize(1);
        docs.save("d",new JdbcDocuments.Save("new","replacement",1),null);assertThat(docs.search("needle")).isEmpty();assertThatThrownBy(()->docs.delete("d",1)).isInstanceOf(IllegalStateException.class);docs.delete("d",2);assertThat(docs.chunks("d")).isEmpty();
    }
    @Test void forgettingAnEpisodeCannotBeUndoneBySdkHistoryReplay(){
        var jobs=new JdbcMemoryJobs(jdbc,source,3,120000,1);var episode=new EpisodeStore.Episode("forget","s",1,"user","private text");jobs.append(episode,true,true);String hash=jdbc.queryForObject("SELECT md5(text) FROM messages WHERE id='forget'",String.class);
        new org.celanwang.mio.controller.MemoryRetentionController(jdbc,source).forget("forget","s",hash);assertThat(jobs.append(episode,true,true)).isEqualTo(EpisodeStore.AppendResult.EXISTING);assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE id='forget'",Long.class)).isZero();
    }
    @Test void indexingResumesInBatchesAndPausedWorkerCannotPublish() {
        var embedding=mock(EmbeddingClient.class);when(embedding.available()).thenReturn(true);when(embedding.namespace()).thenReturn("test-model");
        float[] vector=new float[1024];vector[0]=1;
        var docs=new JdbcDocuments(jdbc,source,store).withKnowledge(embedding,mock(MemoryCallBudget.class));
        docs.save("indexed",new JdbcDocuments.Save("long","evidence ".repeat(3000),0),null);
        var jobs=new DocumentIndexJobs(jdbc,source,docs);jobs.start("indexed",1);
        var stale=jobs.claim().orElseThrow();
        when(embedding.embed(anyList())).thenAnswer(call->{jobs.pause("indexed",stale.id());return Optional.of(Collections.nCopies(((List<?>)call.getArgument(0)).size(),vector));});
        jobs.run(stale);assertThat(jobs.status("indexed").get(0)).containsEntry("state","PAUSED").containsEntry("indexed",0L);
        doAnswer(call->Optional.of(Collections.nCopies(((List<?>)call.getArgument(0)).size(),vector))).when(embedding).embed(anyList());
        jobs.start("indexed",1);jobs.run(jobs.claim().orElseThrow());
        assertThat(jobs.status("indexed").get(0)).containsEntry("state","QUEUED").containsEntry("indexed",16L);
        jdbc.update("UPDATE document_index_jobs SET next_at=0");jobs.run(jobs.claim().orElseThrow());assertThat(jobs.status("indexed").get(0)).containsEntry("state","COMPLETED");
        assertThat(docs.citation("indexed",1,0,8,"evidence")).containsEntry("verified",true);
        assertThatThrownBy(()->docs.citation("indexed",1,0,8,"wrong")).isInstanceOf(IllegalArgumentException.class);
        jobs.start("indexed",1);var old=jobs.claim().orElseThrow();docs.save("indexed",new JdbcDocuments.Save("new","new source",1),null);jobs.run(old);
        assertThat(jobs.status("indexed").get(0)).containsEntry("state","CANCELLED");
    }
    @Test void comparisonsRemainHistoricalAfterThePlanChanges(){
        var registry=new AgentRegistry(List.of(new AgentCapability("domain","domain","domain","s","v1",List.of())));
        var programs=new JdbcGoalPrograms(jdbc,source,json,store,registry);var step=new GoalProgram.Step("report","domain","prepare",List.of(),List.of(),"report",List.of());var policy=new GoalProgram.Policy(false,System.currentTimeMillis()+60000,5,60,1,Set.of("domain"));
        programs.save("s",new GoalProgram.Request(0,1,Map.of(),List.of(step),policy));
        var runtime=new TaskRuntime(store,registry,(t,g,input,execution)->reactor.core.publisher.Mono.just(new TaskWorker.Result("report",false,List.of(),null)),new org.celanwang.mio.security.PolicyEngine("shadow"),java.time.Clock.systemUTC(),16);
        var comparisons=new GoalComparisons(jdbc,programs,runtime,registry,json);
        var request=new GoalComparison.Request(1,1,List.of(),List.of(new GoalComparison.Criterion("durationSeconds","秒",GoalComparison.Direction.MIN,java.math.BigDecimal.ONE,null,null)));
        comparisons.compare("s",request);assertThat(comparisons.recent("s").get(0)).containsEntry("current",true);
        programs.save("s",new GoalProgram.Request(1,1,Map.of(),List.of(step),policy));assertThat(comparisons.recent("s").get(0)).containsEntry("current",false);
        assertThatThrownBy(()->comparisons.compare("s",request)).hasMessageContaining("已改变");
    }
}
