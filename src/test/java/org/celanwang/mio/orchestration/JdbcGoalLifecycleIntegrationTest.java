package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.security.PolicyEngine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import reactor.core.publisher.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfEnvironmentVariable(named="MIO_TEST_PG_URL",matches="jdbc:postgresql:.*")
class JdbcGoalLifecycleIntegrationTest {
    JdbcTemplate admin,jdbc;javax.sql.DataSource source;String schema;final ObjectMapper json=new ObjectMapper();
    JdbcCoordinationStore store;JdbcExecutionLeaseStore leases;JdbcTaskQueue queue;JdbcGoalLifecycle lifecycle;JdbcGoalBudgetStore budgets;
    final AgentRegistry registry=new AgentRegistry(List.of(new AgentCapability("domain","domain","desc","s","v1",List.of())));
    @BeforeEach void initialize(){
        var base=new DriverManagerDataSource(System.getenv("MIO_TEST_PG_URL"),System.getenv().getOrDefault("MIO_TEST_PG_USERNAME","postgres"),System.getenv().getOrDefault("MIO_TEST_PG_PASSWORD",""));
        admin=new JdbcTemplate(base);schema="mio_eval_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        source=new AbstractDataSource(){
            private Connection scope(Connection c)throws SQLException{try(var s=c.createStatement()){s.execute("SET search_path TO "+schema);return c;}catch(SQLException failure){c.close();throw failure;}}
            public Connection getConnection()throws SQLException{return scope(base.getConnection());}public Connection getConnection(String u,String p)throws SQLException{return scope(base.getConnection(u,p));}
        };
        jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE events(id TEXT PRIMARY KEY,ts BIGINT NOT NULL,session_id TEXT,node TEXT,type TEXT,title TEXT,detail TEXT)");
        store=new JdbcCoordinationStore(jdbc,source,json);store.initialize();leases=new JdbcExecutionLeaseStore(jdbc,source);leases.initialize();queue=new JdbcTaskQueue(jdbc,source,json,store,leases);queue.initialize();
        lifecycle=new JdbcGoalLifecycle(jdbc,source,store,queue);budgets=new JdbcGoalBudgetStore(jdbc,source,json);budgets.initialize();store.createGoal(new GoalRecord("g","root",1,"intent","intent",0,0));budgets.open("g",new GoalBudgetStore.Limits(50,50,50,10000));
    }
    @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    TaskRecord task(String id,TaskStatus status,ExecutionLeaseStore.Token lease){
        var spec=new TaskSpec(id,"domain","task",null,null,TaskScope.REQUEST_APPROVAL);
        var approvals=status==TaskStatus.AWAITING_APPROVAL?List.of(new PendingApproval("approval","call","write","confirm",Map.of(),"h","c",System.currentTimeMillis()+100000,null)):List.<PendingApproval>of();
        var task=new TaskRecord(id,"root","g",1,spec,"child-"+id,"v1",status,"reply",null,approvals,false,List.of(),0,0,0,List.of(),List.of(),null,lease);store.createTask(task);return task;
    }
    TaskRuntime runtime(List<OperationReconciler> reconcilers){return new TaskRuntime(store,registry,(t,g,i,e)->Mono.error(new AssertionError("no worker")),new PolicyEngine("shadow"),Clock.systemUTC(),16,List.of(),reconcilers,List.of(),leases,queue);}
    @Test void pauseRevokesExecutionAndApprovalsButKeepsUnstartedWorkAndBudget(){
        var token=leases.acquire("root",30000);task("running",TaskStatus.RUNNING,token);task("approval",TaskStatus.AWAITING_APPROVAL,null);var question=task("question",TaskStatus.NEEDS_INPUT,null);
        var queued=new TaskRecord("queued","root","g",1,new TaskSpec("queued","domain","prepare",null,null,null,null,"default",TaskSpec.Dispatch.QUEUED),"child-q","v1",TaskStatus.QUEUED,"queued",null,List.of(),false,List.of(),0,0,0);queue.enqueue(queued);
        var before=budgets.find("g").orElseThrow();var paused=lifecycle.transition("root",0,GoalRecord.State.PAUSED);
        assertThat(paused.revision()).isEqualTo(1);assertThat(paused.controlRevision()).isEqualTo(1);assertThat(paused.trustedIntent()).isEqualTo("intent");assertThat(leases.valid(token)).isFalse();
        assertThat(store.task("running").orElseThrow().status()).isEqualTo(TaskStatus.FAILED);assertThat(store.task("approval").orElseThrow().status()).isEqualTo(TaskStatus.STALE);
        assertThat(store.task("approval").orElseThrow().approvals()).isEmpty();assertThat(GoalOverview.of(paused,store.tasks("root")).invalidated()).isEqualTo(1);assertThat(store.task("question").orElseThrow().questionId()).isEqualTo(question.questionId());
        assertThat(store.task("queued").orElseThrow().status()).isEqualTo(TaskStatus.QUEUED);assertThat(budgets.find("g").orElseThrow()).isEqualTo(before);
        var resumed=lifecycle.transition("root",1,GoalRecord.State.ACTIVE);assertThat(resumed.controlRevision()).isEqualTo(2);assertThat(store.task("running").orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(queue.find("queued").orElseThrow().state()).isEqualTo(TaskQueue.State.READY);
    }
    @Test void auditFailureRollsBackGoalTaskAndLeaseAndStaleControlDoesNotInterrupt(){
        var token=leases.acquire("root",30000);var running=task("running",TaskStatus.RUNNING,token);
        jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_stop CHECK(type <> 'goal_task_stopped')");
        assertThatThrownBy(()->lifecycle.transition("root",0,GoalRecord.State.PAUSED)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.goal("root").orElseThrow().state()).isEqualTo(GoalRecord.State.ACTIVE);assertThat(store.task("running").orElseThrow()).isEqualTo(running);assertThat(leases.valid(token)).isTrue();
        assertThatThrownBy(()->lifecycle.transition("root",99,GoalRecord.State.CLOSED)).isInstanceOf(IllegalStateException.class);assertThat(leases.valid(token)).isTrue();
    }
    @Test void closeKeepsUnknownEvidenceAndAccountClaimUntilATrustedLateReceipt(){
        var token=leases.acquire("root",30000);var running=task("running",TaskStatus.RUNNING,token);String scope="b".repeat(64);
        var op=new OperationRecord("op","call","write","h",1,"SUBMITTING","",OperationOutcome.UNKNOWN,"",List.of(),UUID.randomUUID().toString(),scope);store.startOperation(running,op,1);
        var queued=new TaskRecord("queued","root","g",1,new TaskSpec("queued","domain","prepare",null,null,null,null,"default",TaskSpec.Dispatch.QUEUED),"child-q","v1",TaskStatus.QUEUED,"queued",null,List.of(),false,List.of(),0,0,0);queue.enqueue(queued);
        lifecycle.transition("root",0,GoalRecord.State.CLOSED);var stopped=store.task("running").orElseThrow();assertThat(stopped.status()).isEqualTo(TaskStatus.UNKNOWN);assertThat(stopped.unresolvedEffects()).isTrue();
        assertThat(store.task("queued").orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);assertThat(queue.find("queued").orElseThrow().state()).isEqualTo(TaskQueue.State.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_claims",Integer.class)).isEqualTo(1);assertThatThrownBy(()->lifecycle.transition("root",1,GoalRecord.State.ACTIVE)).isInstanceOf(IllegalStateException.class);
        var newTask=task("new-task",TaskStatus.RUNNING,null);assertThatThrownBy(()->store.startOperation(newTask,new OperationRecord("new-op","c","write","h",1,"SUBMITTING",""),1)).isInstanceOf(GoalRecord.Stopped.class);
        store.recordReceipt("running","op",new OperationReceipt("receipt","trusted-fixture","SUCCESS","proof",OperationOutcome.CONFIRMED_APPLIED,"business",2),2);
        assertThat(store.goal("root").orElseThrow().state()).isEqualTo(GoalRecord.State.CLOSED);assertThat(store.task("running").orElseThrow().status()).isEqualTo(TaskStatus.UNKNOWN);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_claims",Integer.class)).isZero();
    }
    @Test void stoppedGoalCanBeReconciledWithoutAnyModelOrBudgetReset(){
        var token=leases.acquire("root",30000);var t=task("running",TaskStatus.RUNNING,token);var op=new OperationRecord("op","call","write","hash",1,"SUBMITTING","");store.startOperation(t,op,1);lifecycle.transition("root",0,GoalRecord.State.CLOSED);
        var checks=new AtomicInteger();var adapter=new OperationReconciler(){public boolean supports(OperationRecord o){return o.tool().equals("write");}public Mono<OperationReceipt> reconcile(TaskRecord a,OperationRecord b){checks.incrementAndGet();return Mono.just(new OperationReceipt("r","trusted-fixture","SUCCESS","proof",OperationOutcome.CONFIRMED_APPLIED,"business",2));}};
        var runtime=runtime(List.of(adapter));var model=mock(io.agentscope.core.model.Model.class);var states=new JdbcAgentStateStore(jdbc,json);states.initialize();
        var service=service(runtime,model,states);var request=new org.celanwang.mio.model.dto.ChatRequest();request.setSessionId("root");request.setMessage("new message");
        assertThatThrownBy(()->service.chat(request).block(Duration.ofSeconds(5))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var reply=service.reconcile("root","running","op").block(Duration.ofSeconds(5));assertThat(reply.getGoal().state()).isEqualTo(GoalRecord.State.CLOSED);assertThat(reply.getGoal().unresolved()).isZero();assertThat(checks.get()).isEqualTo(1);
        assertThat(budgets.find("g").orElseThrow().models()).isZero();assertThat(budgets.find("g").orElseThrow().tools()).isEqualTo(1);verifyNoInteractions(model);
    }
    @Test void pausingAnInFlightSdkModelPreventsItsLateCheckpointButKeepsItsUsage()throws Exception{
        var runtime=runtime(List.of());var states=new JdbcAgentStateStore(jdbc,json);states.initialize();var sink=Sinks.<io.agentscope.core.model.ChatResponse>one();var entered=new CountDownLatch(1);var model=mock(io.agentscope.core.model.Model.class);
        when(model.getModelName()).thenReturn("fake-model");when(model.stream(anyList(),any(),any())).thenAnswer(inv->Flux.defer(()->{entered.countDown();return sink.asMono().flux();}));
        var request=new org.celanwang.mio.model.dto.ChatRequest();request.setSessionId("root");request.setMessage("start");var pending=service(runtime,model,states).chat(request).toFuture();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        lifecycle.transition("root",0,GoalRecord.State.PAUSED);sink.tryEmitValue(io.agentscope.core.model.ChatResponse.builder().content(List.of(io.agentscope.core.message.TextBlock.builder().text("late").build())).usage(io.agentscope.core.model.ChatUsage.builder().inputTokens(5).outputTokens(3).build()).build());
        assertThatThrownBy(()->pending.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);assertThat(states.exists(null,"root:supervisor")).isFalse();assertThat(budgets.find("g").orElseThrow().knownTokens()).isEqualTo(8);
        assertThat(store.goal("root").orElseThrow().state()).isEqualTo(GoalRecord.State.PAUSED);
    }
    @Test void competingControlsOnlyApplyOneExpectedVersion()throws Exception{
        var token=leases.acquire("root",30000);var barrier=new CyclicBarrier(2);var winners=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try{var jobs=List.of(GoalRecord.State.PAUSED,GoalRecord.State.CLOSED).stream().map(state->pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{lifecycle.transition("root",0,state);winners.incrementAndGet();}catch(IllegalStateException stale){}return true;})).toList();for(var job:jobs)job.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertThat(winners.get()).isEqualTo(1);assertThat(store.goal("root").orElseThrow().controlRevision()).isEqualTo(1);assertThat(leases.valid(token)).isFalse();
    }
    private MultiAgentChatService service(TaskRuntime runtime,io.agentscope.core.model.Model model,JdbcAgentStateStore states){return new MultiAgentChatService(new SupervisorFactory(model,states,registry,runtime,json),runtime,registry,
            new RunBudget.Limits(20,20,20,10000,10000,20000),Clock.systemUTC(),new org.celanwang.mio.trace.TraceStore((s,e)->{}),null,null,budgets,new GoalBudgetStore.Limits(50,50,50,10000),leases);}
}
