package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.security.PolicyEngine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import reactor.core.publisher.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MIO_TEST_PG_URL",matches="jdbc:postgresql:.*")
class JdbcTaskQueueIntegrationTest {
    JdbcTemplate admin,jdbc;DriverManagerDataSource base;javax.sql.DataSource source;String schema;
    JdbcCoordinationStore store;JdbcExecutionLeaseStore leases;JdbcTaskQueue queue;final ObjectMapper json=new ObjectMapper();
    final AgentRegistry registry=new AgentRegistry(List.of(new AgentCapability("domain","领域","desc","skill","v1",List.of())));
    @BeforeEach void initialize(){
        base=new DriverManagerDataSource(System.getenv("MIO_TEST_PG_URL"),System.getenv().getOrDefault("MIO_TEST_PG_USERNAME","postgres"),System.getenv().getOrDefault("MIO_TEST_PG_PASSWORD",""));
        admin=new JdbcTemplate(base);schema="mio_eval_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        source=new AbstractDataSource(){
            private Connection scope(Connection c)throws SQLException{try(var s=c.createStatement()){s.execute("SET search_path TO "+schema);return c;}catch(SQLException failure){c.close();throw failure;}}
            public Connection getConnection()throws SQLException{return scope(base.getConnection());}
            public Connection getConnection(String u,String p)throws SQLException{return scope(base.getConnection(u,p));}
        };
        jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE events(id TEXT PRIMARY KEY,ts BIGINT NOT NULL,session_id TEXT,node TEXT,type TEXT,title TEXT,detail TEXT)");
        store=new JdbcCoordinationStore(jdbc,source,json);store.initialize();leases=new JdbcExecutionLeaseStore(jdbc,source);leases.initialize();queue=new JdbcTaskQueue(jdbc,source,json,store,leases);queue.initialize();
        store.createGoal(new GoalRecord("g","root",1,"intent","intent",0,0));
    }
    @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    TaskSpec spec(String key,List<String> deps){return new TaskSpec(key,"domain","prepare",List.of(),deps,TaskScope.PREPARE,List.of(),"default",TaskSpec.Dispatch.QUEUED);}
    TaskRecord task(String id,List<String> deps){return new TaskRecord(id,"root","g",1,spec(id,deps),"child-"+id,"v1",TaskStatus.QUEUED,"queued",null,List.of(),false,List.of(),0,0,0);}
    TaskRuntime runtime(TaskWorker worker){return new TaskRuntime(store,registry,worker,new PolicyEngine("shadow"),Clock.systemUTC(),16,List.of(),List.of(),List.of(),leases,queue);}
    RunBudget budget(ExecutionLeaseStore.Token token){var budget=new RunBudget(new RunBudget.Limits(30,30,20,100000,100000,30000),Clock.systemUTC());budget.bindLease(leases,token);return budget;}
    ExecutionLeaseStore.Token token(){return leases.acquire("root",35000);}
    @Test void enqueueIsAtomicAndDoesNotRunTheWorker(){
        var token=token();var budget=budget(token);var starts=new AtomicInteger();var runtime=runtime((t,g,i,e)->{starts.incrementAndGet();return Mono.error(new AssertionError("must not run"));});
        jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_enqueue CHECK(type <> 'task_enqueued')");
        assertThatThrownBy(()->runtime.submit("root",spec("failed",List.of()),budget).block()).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.tasks("root")).isEmpty();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_queue",Integer.class)).isZero();jdbc.execute("ALTER TABLE events DROP CONSTRAINT reject_enqueue");
        var queued=runtime.submit("root",spec("queued",List.of()),budget).block();assertThat(queued.status()).isEqualTo(TaskStatus.QUEUED);assertThat(starts.get()).isZero();
        assertThat(runtime.submit("root",spec("queued",List.of()),budget).block()).isEqualTo(queued);
        assertThat(queue.find(queued.id()).orElseThrow().state()).isEqualTo(TaskQueue.State.READY);assertThat(runtime.summary(queued).queue().attempts()).isZero();
    }
    @Test void independentClaimersHaveOneWinnerAndTaskStartSharesTheQueueTransaction()throws Exception{
        queue.enqueue(task("queued",List.of()));var token=token();var barrier=new CyclicBarrier(2);var claims=new CopyOnWriteArrayList<TaskQueue.Claim>();var pool=Executors.newFixedThreadPool(2);
        try{
            var jobs=java.util.stream.IntStream.range(0,2).mapToObj(i->pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);try{new JdbcTaskQueue(jdbc,source,json,store,leases).claim("queued",token).ifPresent(claims::add);}catch(IllegalStateException occupied){}return true;})).toList();
            for(var job:jobs)job.get(10,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        assertThat(claims).hasSize(1);var claim=claims.get(0);var expected=store.task("queued").orElseThrow();
        jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_start CHECK(type <> 'queue_started')");
        assertThatThrownBy(()->queue.start(claim,expected,expected.start(token,System.currentTimeMillis()))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.task("queued").orElseThrow().status()).isEqualTo(TaskStatus.QUEUED);assertThat(queue.find("queued").orElseThrow().state()).isEqualTo(TaskQueue.State.CLAIMED);
        jdbc.execute("ALTER TABLE events DROP CONSTRAINT reject_start");queue.start(claim,expected,expected.start(token,System.currentTimeMillis()));
        assertThat(store.task("queued").orElseThrow().status()).isEqualTo(TaskStatus.RUNNING);assertThat(queue.find("queued").orElseThrow().state()).isEqualTo(TaskQueue.State.STARTED);
        assertThatThrownBy(()->queue.start(claim,expected,expected.start(token,System.currentTimeMillis()))).isInstanceOf(IllegalStateException.class);
    }
    @Test void claimLostBeforeStartCanBeReclaimedButOldClaimCannotStart(){
        queue.enqueue(task("queued",List.of()));var old=token();var claim=queue.claim("queued",old).orElseThrow();
        jdbc.update("UPDATE execution_leases SET expires_at=0 WHERE resource=?",old.resource());var next=token();var recovered=queue.claim("queued",next).orElseThrow();
        assertThat(queue.find("queued").orElseThrow().attempts()).isEqualTo(2);var expected=store.task("queued").orElseThrow();
        assertThatThrownBy(()->queue.start(claim,expected,expected.start(old,System.currentTimeMillis()))).isInstanceOf(IllegalStateException.class);
        queue.start(recovered,expected,expected.start(next,System.currentTimeMillis()));assertThat(queue.find("queued").orElseThrow().state()).isEqualTo(TaskQueue.State.STARTED);
    }
    @Test void taskCompletionWithoutQueueAckIsReconciledWithoutASecondExecution(){
        queue.enqueue(task("queued",List.of()));var token=token();var claim=queue.claim("queued",token).orElseThrow();var expected=store.task("queued").orElseThrow();
        queue.start(claim,expected,expected.start(token,System.currentTimeMillis()));var running=store.task("queued").orElseThrow();store.saveTask(running,running.next(TaskStatus.COMPLETED,"done",List.of(),false,System.currentTimeMillis()),"task_updated");
        jdbc.execute("ALTER TABLE events ADD CONSTRAINT reject_settle CHECK(type <> 'queue_settled')");
        assertThatThrownBy(()->queue.settle(claim)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(queue.find("queued").orElseThrow().state()).isEqualTo(TaskQueue.State.STARTED);assertThat(store.task("queued").orElseThrow().status()).isEqualTo(TaskStatus.COMPLETED);
        jdbc.execute("ALTER TABLE events DROP CONSTRAINT reject_settle");var reloaded=new JdbcTaskQueue(jdbc,source,json,store,leases);assertThat(reloaded.sync("queued").state()).isEqualTo(TaskQueue.State.DONE);
        long revision=reloaded.find("queued").orElseThrow().revision();assertThat(reloaded.settle(claim).revision()).isEqualTo(revision);assertThat(reloaded.claim("queued",token)).isEmpty();
    }
    @Test void startedAbandonedTaskIsDeadAndNeverReturnedToReady(){
        var sink=Sinks.<TaskWorker.Result>one();var starts=new AtomicInteger();var errors=new AtomicReference<Throwable>();var runtime=runtime((t,g,i,e)->{starts.incrementAndGet();return sink.asMono();});
        var old=token();var queued=runtime.submit("root",spec("queued",List.of()),budget(old)).block();var subscription=runtime.runQueuedTask("root",queued.id(),budget(old)).subscribe(v->{},errors::set);
        assertThat(queue.find(queued.id()).orElseThrow().state()).isEqualTo(TaskQueue.State.STARTED);
        jdbc.update("UPDATE execution_leases SET expires_at=0 WHERE resource=?",old.resource());var next=token();runtime.list("root");
        assertThat(store.task(queued.id()).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);assertThat(queue.find(queued.id()).orElseThrow().state()).isEqualTo(TaskQueue.State.DEAD);
        assertThatThrownBy(()->runtime.runQueuedTask("root",queued.id(),budget(next)).block()).isInstanceOf(IllegalStateException.class);
        sink.tryEmitValue(new TaskWorker.Result("old success",false,List.of(),null));assertThat(starts.get()).isEqualTo(1);assertThat(errors.get()).isInstanceOf(ExecutionLeaseStore.LostLease.class);subscription.dispose();
    }
    @Test void queuedDependenciesWaitAndCancelledSourcesInvalidateBeforeAnyClaim(){
        var parent=new TaskRecord("parent","root","g",1,new TaskSpec("parent","domain","parent",null,null,TaskScope.PREPARE),"child","v1",TaskStatus.NEEDS_INPUT,"question",null,List.of(),false,List.of(),0,0,0);store.createTask(parent);
        var token=token();var runtime=runtime((t,g,i,e)->Mono.error(new AssertionError("must not start")));var queued=runtime.submit("root",spec("queued",List.of(parent.id())),budget(token)).block();
        assertThat(runtime.require("root",queued.id()).status()).isEqualTo(TaskStatus.QUEUED);
        assertThatThrownBy(()->runtime.runQueuedTask("root",queued.id(),budget(token)).block()).isInstanceOf(TaskFacts.InputUnavailable.class);
        assertThat(queue.find(queued.id()).orElseThrow().attempts()).isZero();runtime.cancel("root",parent.id());runtime.list("root");
        assertThat(store.task(queued.id()).orElseThrow().status()).isEqualTo(TaskStatus.STALE);assertThat(queue.find(queued.id()).orElseThrow().state()).isEqualTo(TaskQueue.State.STALE);
    }
    @Test void prestartBudgetFailureReleasesTheClaimWithoutRunningAModel(){
        queue.enqueue(task("queued",List.of()));var token=token();var budget=new RunBudget(new RunBudget.Limits(10,10,1,10000,10000,10000),Clock.systemUTC());budget.bindLease(leases,token);budget.task();
        var starts=new AtomicInteger();var runtime=runtime((t,g,i,e)->{starts.incrementAndGet();return Mono.never();});
        assertThatThrownBy(()->runtime.runQueuedTask("root","queued",budget).block()).isInstanceOf(RunBudget.BudgetExceeded.class);
        assertThat(starts.get()).isZero();assertThat(store.task("queued").orElseThrow().status()).isEqualTo(TaskStatus.QUEUED);assertThat(queue.find("queued").orElseThrow().state()).isEqualTo(TaskQueue.State.READY);
        for(int i=0;i<2;i++){var claim=queue.claim("queued",token).orElseThrow();queue.settle(claim);}
        assertThat(queue.claim("queued",token)).isEmpty();assertThat(store.task("queued").orElseThrow().status()).isEqualTo(TaskStatus.FAILED);assertThat(queue.find("queued").orElseThrow().state()).isEqualTo(TaskQueue.State.DEAD);
    }
    @Test void scopedQueueNeverAcceptsWritesAndGlobalCorrectionsCloseOldQueuedWork()throws Exception{
        assertThatThrownBy(()->new TaskSpec("write","domain","write",null,null,TaskScope.REQUEST_APPROVAL,null,"default",TaskSpec.Dispatch.QUEUED)).isInstanceOf(IllegalArgumentException.class);
        var token=token();var runtime=runtime((t,g,i,e)->Mono.error(new AssertionError("must not start")));var queued=runtime.submit("root",spec("queued",List.of()),budget(token)).block();
        runtime.captureUser("root","修改需求");runtime.list("root");assertThat(queue.find(queued.id()).orElseThrow().state()).isEqualTo(TaskQueue.State.STALE);
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(new TaskSpec("old","domain","old",null,null,null));node.remove("dispatch");
        assertThat(json.treeToValue(node,TaskSpec.class).dispatch()).isEqualTo(TaskSpec.Dispatch.INLINE);
    }
    @Test void aHumanQuestionIsHandedOffInsteadOfBeingAutomaticallyRequeued(){
        var token=token();var runtime=runtime((t,g,i,e)->Mono.just(new TaskWorker.Result("城市？",true,List.of(),null)));var queued=runtime.submit("root",spec("queued",List.of()),budget(token)).block();
        var question=runtime.runQueuedTask("root",queued.id(),budget(token)).block();assertThat(question.status()).isEqualTo(TaskStatus.NEEDS_INPUT);assertThat(queue.find(queued.id()).orElseThrow().state()).isEqualTo(TaskQueue.State.HANDED_OFF);
        assertThat(queue.claim(queued.id(),token)).isEmpty();
        runtime.cancel("root",queued.id());assertThat(runtime.list("root").get(0).status()).isEqualTo(TaskStatus.CANCELLED);
    }
    @Test void actualSupervisorQueuesAndAControlRequestRunsTheDomainWithoutRevisingTheGoal()throws Exception{
        var states=new JdbcAgentStateStore(jdbc,json);states.initialize();var budgets=new JdbcGoalBudgetStore(jdbc,source,json);budgets.initialize();
        var roots=new ArrayDeque<List<io.agentscope.core.message.ContentBlock>>();var children=new ArrayDeque<List<io.agentscope.core.message.ContentBlock>>();var reads=new AtomicInteger();
        roots.add(call("delegate_task",Map.of("key","queued","capabilityId","domain","objective","查询测试数据","dispatch","QUEUED")));
        roots.add(List.of(io.agentscope.core.message.TextBlock.builder().text("已排队，尚未查询").build()));roots.add(List.of(io.agentscope.core.message.TextBlock.builder().text("准备已完成").build()));
        children.add(call("synthetic_query",Map.of()));children.add(call("mio_finish_task",Map.of("text","已取得测试数据")));
        var tool=new io.agentscope.core.tool.ToolBase(io.agentscope.core.tool.ToolBase.builder().name("synthetic_query").description("read fixture").readOnly(true).inputSchema(Map.of())){
            @Override public Mono<io.agentscope.core.message.ToolResultBlock> callAsync(io.agentscope.core.tool.ToolCallParam p){reads.incrementAndGet();return Mono.just(io.agentscope.core.message.ToolResultBlock.text("synthetic data"));}
        };
        var profiles=org.mockito.Mockito.mock(org.celanwang.mio.memory.ProfileStore.class);org.mockito.Mockito.when(profiles.pages()).thenReturn(List.of());org.mockito.Mockito.when(profiles.render(org.mockito.ArgumentMatchers.anyList())).thenReturn("");
        @SuppressWarnings("unchecked") org.springframework.beans.factory.ObjectProvider<io.agentscope.extensions.judge.jev.JevClient> jev=org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        var policy=new PolicyEngine("shadow");var factory=new DomainAgentFactory(List.of(new DomainDefinition(registry.require("domain"),"test domain",()->List.of(tool))),c->model(children),states,null,policy,
                org.mockito.Mockito.mock(org.celanwang.mio.service.WeiboMcpService.class),jev,.8,new org.celanwang.mio.trace.TraceStore((session,event)->{}),org.mockito.Mockito.mock(org.celanwang.mio.memory.TrustStore.class),
                org.mockito.Mockito.mock(org.celanwang.mio.memory.MioLongTermMemory.class),org.mockito.Mockito.mock(org.celanwang.mio.memory.HabitDetector.class),profiles,json);
        var runtime=runtime(factory);var service=new MultiAgentChatService(new SupervisorFactory(model(roots),states,registry,runtime,json),runtime,registry,new RunBudget.Limits(30,30,20,100000,100000,30000),Clock.systemUTC(),
                new org.celanwang.mio.trace.TraceStore((session,event)->{}),null,null,budgets,new GoalBudgetStore.Limits(100,100,100,100000),leases);
        var request=new org.celanwang.mio.model.dto.ChatRequest();request.setSessionId("root");request.setMessage("只查询测试数据，不操作业务");
        var first=service.chat(request).block(java.time.Duration.ofSeconds(10));assertThat(first.getTasks()).hasSize(1);var task=store.tasks("root").get(0);var goal=runtime.goal("root");
        assertThat(task.status()).isEqualTo(TaskStatus.QUEUED);assertThat(reads.get()).isZero();assertThat(states.exists(null,task.childSessionId())).isFalse();
        var request2=new org.celanwang.mio.model.dto.ChatRequest();request2.setSessionId("root");request2.setResumeTaskId(task.id());
        var second=service.chat(request2).block(java.time.Duration.ofSeconds(10));assertThat(second.getTasks().get(0).status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(second.getTasks().get(0).queue().state()).isEqualTo(TaskQueue.State.DONE);assertThat(reads.get()).isEqualTo(1);assertThat(runtime.goal("root")).isEqualTo(goal);
        assertThat(budgets.find("g").orElseThrow().tasks()).isEqualTo(1);assertThat(budgets.find("g").orElseThrow().models()).isEqualTo(5);
        assertThatThrownBy(()->service.chat(request2).block(java.time.Duration.ofSeconds(10))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);assertThat(reads.get()).isEqualTo(1);
    }
    @Test void cancellationAfterClaimClosesDispatchAndRejectsItsLateStart(){
        queue.enqueue(task("queued",List.of()));var token=token();var claim=queue.claim("queued",token).orElseThrow();var expected=store.task("queued").orElseThrow();
        var runtime=runtime((t,g,i,e)->Mono.error(new AssertionError("must not run")));var cancelled=runtime.cancel("root","queued");
        assertThat(runtime.summary(cancelled).queue().state()).isEqualTo(TaskQueue.State.CANCELLED);
        assertThatThrownBy(()->queue.start(claim,expected,expected.start(token,System.currentTimeMillis()))).isInstanceOf(IllegalStateException.class);
        assertThat(store.task("queued").orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
    }
    @Test void corruptQueueMetadataStopsClaimsInsteadOfTrustingTheIndex(){
        queue.enqueue(task("queued",List.of()));jdbc.update("UPDATE task_queue SET state='STARTED' WHERE task_id='queued'");
        assertThatThrownBy(()->queue.claim("queued",token())).isInstanceOf(IllegalStateException.class).hasMessageContaining("不一致");
        assertThat(store.task("queued").orElseThrow().status()).isEqualTo(TaskStatus.QUEUED);
    }
    private List<io.agentscope.core.message.ContentBlock> call(String name,Map<String,Object> args)throws Exception{
        return List.of(io.agentscope.core.message.ToolUseBlock.builder().id(UUID.randomUUID().toString()).name(name).input(args).content(json.writeValueAsString(args)).build());
    }
    private io.agentscope.core.model.Model model(Deque<List<io.agentscope.core.message.ContentBlock>> script){
        var model=org.mockito.Mockito.mock(io.agentscope.core.model.Model.class);org.mockito.Mockito.when(model.getModelName()).thenReturn("fake-model");
        org.mockito.Mockito.when(model.stream(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(inv->Flux.defer(()->{
            if(script.isEmpty())return Flux.error(new IllegalStateException("script exhausted"));return Flux.just(io.agentscope.core.model.ChatResponse.builder().content(script.removeFirst()).usage(io.agentscope.core.model.ChatUsage.builder().inputTokens(5).outputTokens(3).build()).build());
        }));return model;
    }

}
