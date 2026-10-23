package org.celanwang.mio.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.orchestration.*;
import org.celanwang.mio.security.PolicyEngine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MIO_TEST_PG_URL",matches="jdbc:postgresql:.*")
class JdbcGoalProgramsIntegrationTest {
    JdbcTemplate admin,jdbc;String schema;JdbcCoordinationStore store;JdbcGoalPrograms programs;JdbcGoalWakeups wakeups;GoalProgramExecutor executor;TaskRuntime runtime;final AtomicInteger calls=new AtomicInteger();
    java.util.concurrent.CyclicBarrier barrier;
    @BeforeEach void init(){
        String url=System.getenv("MIO_TEST_PG_URL"),user=System.getenv().getOrDefault("MIO_TEST_PG_USERNAME","postgres"),password=System.getenv().getOrDefault("MIO_TEST_PG_PASSWORD","");admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));schema="mio_program_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        var source=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);jdbc=new JdbcTemplate(source);var json=new ObjectMapper();jdbc.execute("CREATE TABLE events(id TEXT PRIMARY KEY,ts BIGINT NOT NULL,session_id TEXT,node TEXT,type TEXT,title TEXT,detail TEXT)");
        store=new JdbcCoordinationStore(jdbc,source,json);store.initialize();store.createGoal(new GoalRecord("g","s",1,"prepare","prepare",1,1));var leases=new JdbcExecutionLeaseStore(jdbc,source);leases.initialize();var budgets=new JdbcGoalBudgetStore(jdbc,source,json);budgets.initialize();var queue=new JdbcTaskQueue(jdbc,source,json,store,leases);queue.initialize();
        var registry=new AgentRegistry(List.of(new AgentCapability("domain","domain","domain","s","v1",List.of()),new AgentCapability("other-domain","other","other","s","v1",List.of())));programs=new JdbcGoalPrograms(jdbc,source,json,store,registry);programs.initialize();wakeups=new JdbcGoalWakeups(jdbc,source,programs,store);wakeups.initialize();store.onTaskChanged(wakeups::signal);
        runtime=new TaskRuntime(store,registry,(task,goal,input,execution)->Mono.fromCallable(()->{calls.incrementAndGet();if(barrier!=null)barrier.await(3,java.util.concurrent.TimeUnit.SECONDS);return new TaskWorker.Result("prepared",false,List.of(),null);}),new PolicyEngine("shadow"),Clock.systemUTC(),32,List.of(),List.of(),List.of(),leases,queue);
        executor=new GoalProgramExecutor(programs,wakeups,runtime,leases,budgets,registry,new RunBudget.Limits(20,20,20,1000,10000,10000),new GoalBudgetStore.Limits(100,100,100,10000));
    }
    @AfterEach void cleanup(){admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    GoalProgram.Request request(long revision,String place){return new GoalProgram.Request(revision,1,Map.of("place",place,"budget","100"),List.of(new GoalProgram.Step("route","domain","route",List.of("place"),List.of(),"report",List.of()),new GoalProgram.Step("compare","domain","compare",List.of("budget"),List.of("route"),"report",List.of()),new GoalProgram.Step("other","domain","other",List.of("budget"),List.of(),"report",List.of())),new GoalProgram.Policy(false,System.currentTimeMillis()+3600000,5,60,1,Set.of("domain")));}
    @Test void fieldEditsInvalidateOnlyAffectedStepsAndTheirDependants(){
        var first=programs.save("s",request(0,"上海"));wakeups.wake("s","one");executor.run(wakeups.claim().orElseThrow());assertThat(calls.get()).isEqualTo(3);
        var second=programs.save("s",request(1,"杭州"));assertThat(second.generations()).containsEntry("route",2L).containsEntry("compare",2L).containsEntry("other",1L);
        assertThat(store.tasks("s").stream().filter(t->t.spec().key().equals(first.taskKey(first.steps().get(0)))).findFirst().orElseThrow().invalidation()).isNotNull();
        wakeups.wake("s","two");executor.run(wakeups.claim().orElseThrow());assertThat(calls.get()).isEqualTo(5);
        assertThatThrownBy(()->programs.save("s",request(1,"其他"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void duplicateWakeupsAndRestartedExecutorNeverReplayCompletedPreparation(){
        programs.save("s",request(0,"上海"));wakeups.wake("s","same");wakeups.wake("s","same");assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_wakeups",Long.class)).isEqualTo(1);executor.run(wakeups.claim().orElseThrow());
        wakeups.wake("s","next");executor.run(wakeups.claim().orElseThrow());assertThat(calls.get()).isEqualTo(3);assertThat(jdbc.queryForObject("SELECT activations FROM goal_programs",Integer.class)).isEqualTo(2);
        assertThat(executor.status("s")).containsKey("steps");assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_wakeups WHERE state='DONE'",Long.class)).isEqualTo(2);
    }
    @Test void removedStepIdsCannotReuseTheirOldTaskGeneration(){
        var first=programs.save("s",request(0,"上海"));wakeups.wake("s","one");executor.run(wakeups.claim().orElseThrow());
        programs.save("s",new GoalProgram.Request(1,1,Map.of(),List.of(),first.policy()));var readded=programs.save("s",request(2,"上海"));assertThat(readded.generations()).containsEntry("route",3L).containsEntry("other",3L);
        wakeups.wake("s","two");executor.run(wakeups.claim().orElseThrow());assertThat(calls.get()).isEqualTo(6);
    }
    @Test void independentCapabilitiesActuallyRunInParallelWithinTheConfirmedLimit(){
        barrier=new java.util.concurrent.CyclicBarrier(2);var a=new GoalProgram.Step("a","domain","a",List.of(),List.of(),"report",List.of());var b=new GoalProgram.Step("b","other-domain","b",List.of(),List.of(),"report",List.of());
        programs.save("s",new GoalProgram.Request(0,1,Map.of(),List.of(a,b),new GoalProgram.Policy(false,System.currentTimeMillis()+60000,5,60,2,Set.of("domain","other-domain"))));wakeups.wake("s","parallel");executor.run(wakeups.claim().orElseThrow());
        assertThat(calls.get()).isEqualTo(2);assertThat(store.tasks("s")).allMatch(task->task.status()==TaskStatus.COMPLETED);
    }
    @Test void documentsKeepVersionsAndResourceReportsNeverBecomeProviderEvidence(){
        var documents=new JdbcDocuments(jdbc,Objects.requireNonNull(jdbc.getDataSource()),store);documents.initialize();var doc=documents.save("notes",new JdbcDocuments.Save("notes","原始资料",0),null);assertThat(documents.search("资料")).hasSize(1);assertThatThrownBy(()->documents.save("notes",new JdbcDocuments.Save("notes","changed",0),null)).isInstanceOf(IllegalStateException.class);assertThat(documents.read("notes").revision()).isEqualTo(1);
        var resources=new JdbcBusinessResources(jdbc,jdbc.getDataSource(),store,new ObjectMapper());resources.initialize();store.onTaskChanged(task->{wakeups.signal(task);resources.changed(task);});
        var receipt=new OperationReceipt("receipt","trusted-fixture","SUCCESS","",OperationOutcome.CONFIRMED_APPLIED,"business",1);var operation=new OperationRecord("operation","call","write","hash",1,"VERIFIED","",OperationOutcome.CONFIRMED_APPLIED,"business",List.of(receipt));
        var task=new TaskRecord("written","s","g",1,new TaskSpec("written","domain","write",List.of(),List.of(),TaskScope.REQUEST_APPROVAL),"child","v1",TaskStatus.RUNNING,"",null,List.of(),true,List.of(operation),0,1,1);store.createTask(task);store.saveTask(task,task.next(TaskStatus.COMPLETED,"created",List.of(),true,2),"fixture_complete");
        var current=resources.list("s").get(0);assertThat(current.get("state")).isEqualTo("CREATED");resources.report("s","operation",new JdbcBusinessResources.Report(JdbcBusinessResources.State.COMPLETED,"人工在供应商端核实",(String)current.get("observation_id")));
        assertThat(resources.list("s").get(0)).containsEntry("state","COMPLETED").containsEntry("source","USER_REPORTED");assertThat(resources.history("s","operation")).hasSize(2);assertThat(store.task("written").orElseThrow().operations()).containsExactly(operation);
    }
}
